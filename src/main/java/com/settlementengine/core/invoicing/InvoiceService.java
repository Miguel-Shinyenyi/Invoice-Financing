package com.settlementengine.core.invoicing;

import com.settlementengine.core.domain.AccountNotFoundException;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.fraud.FraudCheckRequest;
import com.settlementengine.core.fraud.FraudCheckResult;
import com.settlementengine.core.fraud.FraudDetectionClient;
import com.settlementengine.core.repository.FraudAssessmentRepository;
import com.settlementengine.core.repository.InvoiceRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final SettlementService settlementService;
    private final InvoiceTransactions invoiceTransactions;
    private final FraudDetectionClient fraudDetectionClient;
    private final FraudAssessmentRepository fraudAssessmentRepository;
    private final BigDecimal advanceRate;
    private final BigDecimal feeRate;
    private final UUID platformAccountId;

    public InvoiceService(InvoiceRepository invoiceRepository,
                           LedgerAccountRepository ledgerAccountRepository,
                           SettlementService settlementService,
                           InvoiceTransactions invoiceTransactions,
                           FraudDetectionClient fraudDetectionClient,
                           FraudAssessmentRepository fraudAssessmentRepository,
                           @Value("${settlement-engine.invoice-financing.advance-rate-percent:80}") int advanceRatePercent,
                           @Value("${settlement-engine.invoice-financing.fee-rate-percent:2}") int feeRatePercent,
                           @Value("${settlement-engine.invoice-financing.platform-account-id}") UUID platformAccountId) {
        this.invoiceRepository = invoiceRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.settlementService = settlementService;
        this.invoiceTransactions = invoiceTransactions;
        this.fraudDetectionClient = fraudDetectionClient;
        this.fraudAssessmentRepository = fraudAssessmentRepository;
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
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        LedgerAccount businessAccount = ledgerAccountRepository.findById(invoice.getBusinessAccountId())
                .orElseThrow(() -> new AccountNotFoundException(invoice.getBusinessAccountId()));

        BigDecimal amountAdvanced = invoice.getAmount().multiply(advanceRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = invoice.getAmount().multiply(feeRate).setScale(2, RoundingMode.HALF_UP);

        runFraudCheck(invoice, businessAccount, amountAdvanced);

        Invoice claimed = invoiceTransactions.claimForFinancing(invoiceId);

        CreateSettlementCommand disbursement = new CreateSettlementCommand(
                platformAccountId, claimed.getBusinessAccountId(), amountAdvanced, claimed.getCurrency());
        SettlementResult result = settlementService.createSettlement(idempotencyKey, disbursement);

        return invoiceTransactions.recordAdvance(invoiceId, amountAdvanced, fee, result.settlementId());
    }

    private void runFraudCheck(Invoice invoice, LedgerAccount businessAccount, BigDecimal requestedAdvanceAmount) {
        long accountAgeDays = Duration.between(businessAccount.getCreatedAt(), Instant.now()).toDays();
        long duplicateCustomerReferenceCount = invoiceRepository.countByCustomerReferenceAndBusinessAccountIdNot(
                invoice.getCustomerReference(), invoice.getBusinessAccountId());
        long outstandingAdvanceCount = invoiceRepository.countByBusinessAccountIdAndStatusIn(
                invoice.getBusinessAccountId(), List.of(InvoiceStatus.FINANCED, InvoiceStatus.OVERDUE));

        FraudCheckRequest request = new FraudCheckRequest(invoice.getId(), invoice.getBusinessAccountId(),
                invoice.getCustomerReference(), invoice.getAmount(), invoice.getCurrency(), requestedAdvanceAmount,
                accountAgeDays, duplicateCustomerReferenceCount, outstandingAdvanceCount);
        FraudCheckResult result = fraudDetectionClient.check(request);

        String reasons = result.reasons().isEmpty() ? null : String.join(", ", result.reasons());
        fraudAssessmentRepository.save(
                new FraudAssessment(UUID.randomUUID(), invoice.getId(), result.score(), result.decision(), reasons));

        if (result.decision() == FraudDecision.BLOCK) {
            throw new FraudBlockedException(invoice.getId(), result.score(), result.reasons());
        }
    }
}
