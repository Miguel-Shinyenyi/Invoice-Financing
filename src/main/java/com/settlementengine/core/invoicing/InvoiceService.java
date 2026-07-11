package com.settlementengine.core.invoicing;

import com.settlementengine.core.domain.AccountNotFoundException;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.repository.InvoiceRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final SettlementService settlementService;
    private final InvoiceTransactions invoiceTransactions;
    private final BigDecimal advanceRate;
    private final BigDecimal feeRate;
    private final UUID platformAccountId;

    public InvoiceService(InvoiceRepository invoiceRepository,
                           LedgerAccountRepository ledgerAccountRepository,
                           SettlementService settlementService,
                           InvoiceTransactions invoiceTransactions,
                           @Value("${settlement-engine.invoice-financing.advance-rate-percent:80}") int advanceRatePercent,
                           @Value("${settlement-engine.invoice-financing.fee-rate-percent:2}") int feeRatePercent,
                           @Value("${settlement-engine.invoice-financing.platform-account-id}") UUID platformAccountId) {
        this.invoiceRepository = invoiceRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.settlementService = settlementService;
        this.invoiceTransactions = invoiceTransactions;
        this.advanceRate = BigDecimal.valueOf(advanceRatePercent).movePointLeft(2);
        this.feeRate = BigDecimal.valueOf(feeRatePercent).movePointLeft(2);
        this.platformAccountId = platformAccountId;
    }

    public Invoice submitInvoice(CreateInvoiceCommand command) {
        LedgerAccount businessAccount = ledgerAccountRepository.findById(command.businessAccountId())
                .orElseThrow(() -> new AccountNotFoundException(command.businessAccountId()));

        String externalSourceRef = "INV-" + UUID.randomUUID();
        Invoice invoice = new Invoice(UUID.randomUUID(), businessAccount.getId(), command.customerReference(),
                command.amount(), command.currency(), command.dueDate(), externalSourceRef);
        invoiceRepository.save(invoice);
        return invoice;
    }

    public Advance financeInvoice(UUID invoiceId, UUID idempotencyKey) {
        Invoice invoice = invoiceTransactions.claimForFinancing(invoiceId);

        BigDecimal amountAdvanced = invoice.getAmount().multiply(advanceRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = invoice.getAmount().multiply(feeRate).setScale(2, RoundingMode.HALF_UP);

        CreateSettlementCommand disbursement = new CreateSettlementCommand(
                platformAccountId, invoice.getBusinessAccountId(), amountAdvanced, invoice.getCurrency());
        SettlementResult result = settlementService.createSettlement(idempotencyKey, disbursement);

        return invoiceTransactions.recordAdvance(invoiceId, amountAdvanced, fee, result.settlementId());
    }
}
