package com.settlementengine.core.invoicing;

import com.settlementengine.core.repository.AdvanceRepository;
import com.settlementengine.core.repository.InvoiceRepository;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class InvoiceRepaymentService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceRepaymentService.class);

    private final InvoiceRepository invoiceRepository;
    private final AdvanceRepository advanceRepository;
    private final InvoicePaymentSource invoicePaymentSource;
    private final SettlementService settlementService;
    private final InvoiceTransactions invoiceTransactions;
    private final UUID platformAccountId;
    private final Duration gracePeriod;

    public InvoiceRepaymentService(InvoiceRepository invoiceRepository,
                                    AdvanceRepository advanceRepository,
                                    InvoicePaymentSource invoicePaymentSource,
                                    SettlementService settlementService,
                                    InvoiceTransactions invoiceTransactions,
                                    @Value("${settlement-engine.invoice-financing.platform-account-id}") UUID platformAccountId,
                                    @Value("${settlement-engine.invoice-financing.repayment-grace-period-days:3}") long gracePeriodDays) {
        this.invoiceRepository = invoiceRepository;
        this.advanceRepository = advanceRepository;
        this.invoicePaymentSource = invoicePaymentSource;
        this.settlementService = settlementService;
        this.invoiceTransactions = invoiceTransactions;
        this.platformAccountId = platformAccountId;
        this.gracePeriod = Duration.ofDays(gracePeriodDays);
    }

    /**
     * Checks every FINANCED or OVERDUE invoice for a customer payment. Not wrapped in one
     * transaction, and one invoice's failure doesn't stop the others, for the same reasons as
     * {@code ReconciliationService.runOnce} (see reconciliation.md).
     */
    public void checkRepayments() {
        List<Invoice> candidates = invoiceRepository.findByStatusIn(
                List.of(InvoiceStatus.FINANCED, InvoiceStatus.OVERDUE));
        for (Invoice invoice : candidates) {
            try {
                checkOne(invoice);
            } catch (Exception e) {
                log.error("Failed to check repayment for invoice {}, skipping", invoice.getId(), e);
            }
        }
    }

    private void checkOne(Invoice invoice) {
        Optional<BigDecimal> paid = invoicePaymentSource.checkPayment(invoice.getExternalSourceRef());

        if (paid.isEmpty()) {
            if (invoice.getStatus() == InvoiceStatus.FINANCED
                    && Instant.now().isAfter(invoice.getDueDate().plus(gracePeriod))) {
                invoiceTransactions.markOverdue(invoice.getId());
            }
            return;
        }

        if (paid.get().compareTo(invoice.getAmount()) != 0) {
            log.warn("Detected payment of {} for invoice {} does not match expected amount {}, flagging for review",
                    paid.get(), invoice.getId(), invoice.getAmount());
            return;
        }

        Advance advance = advanceRepository.findByInvoiceId(invoice.getId())
                .orElseThrow(() -> new IllegalStateException("No advance found for financed invoice " + invoice.getId()));

        BigDecimal repaymentAmount = advance.getAmountAdvanced().add(advance.getFee());
        UUID idempotencyKey = UUID.nameUUIDFromBytes(
                ("invoice-repayment-" + invoice.getId()).getBytes(StandardCharsets.UTF_8));
        CreateSettlementCommand collection = new CreateSettlementCommand(
                invoice.getBusinessAccountId(), platformAccountId, repaymentAmount, invoice.getCurrency());
        SettlementResult result = settlementService.createSettlement(idempotencyKey, collection);

        if (result.status() == com.settlementengine.core.domain.SettlementStatus.CONFIRMED) {
            invoiceTransactions.recordRepayment(invoice.getId(), advance, result.settlementId());
        }
    }
}
