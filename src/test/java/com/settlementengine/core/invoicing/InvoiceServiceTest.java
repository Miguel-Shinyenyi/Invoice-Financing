package com.settlementengine.core.invoicing;

import com.settlementengine.core.domain.AccountNotFoundException;
import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.fraud.FraudCheckRequest;
import com.settlementengine.core.fraud.FraudCheckResult;
import com.settlementengine.core.fraud.FraudDetectionClient;
import com.settlementengine.core.repository.FraudAssessmentRepository;
import com.settlementengine.core.repository.InvoiceRepository;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private LedgerAccountRepository ledgerAccountRepository;
    @Mock
    private SettlementService settlementService;
    @Mock
    private InvoiceTransactions invoiceTransactions;
    @Mock
    private FraudDetectionClient fraudDetectionClient;
    @Mock
    private FraudAssessmentRepository fraudAssessmentRepository;

    private InvoiceService invoiceService;
    private final UUID platformAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        invoiceService = new InvoiceService(invoiceRepository, ledgerAccountRepository, settlementService,
                invoiceTransactions, fraudDetectionClient, fraudAssessmentRepository, 80, 2, platformAccountId);
    }

    private Invoice issuedInvoice(UUID invoiceId, UUID businessAccountId) {
        return new Invoice(invoiceId, businessAccountId, "customer-1", new BigDecimal("1000.00"), "USD",
                Instant.now().plus(30, ChronoUnit.DAYS), "INV-ref-1");
    }

    private void stubCleanInvoiceLookup(UUID invoiceId, UUID businessAccountId) {
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(issuedInvoice(invoiceId, businessAccountId)));
        when(ledgerAccountRepository.findById(businessAccountId)).thenReturn(
                Optional.of(new LedgerAccount(businessAccountId, UUID.randomUUID(), new BigDecimal("0.00"), "USD")));
        when(fraudDetectionClient.check(any())).thenReturn(FraudCheckResult.allowNoSignal());
    }

    @Test
    void submitInvoiceCreatesIssuedInvoiceForAnExistingBusinessAccount() {
        UUID businessAccountId = UUID.randomUUID();
        when(ledgerAccountRepository.findById(businessAccountId)).thenReturn(
                Optional.of(new LedgerAccount(businessAccountId, UUID.randomUUID(), new BigDecimal("0.00"), "USD")));
        CreateInvoiceCommand command = new CreateInvoiceCommand(businessAccountId, "customer-1",
                new BigDecimal("1000.00"), "USD", Instant.now().plus(30, ChronoUnit.DAYS));

        Invoice invoice = invoiceService.submitInvoice(command);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(invoice.getBusinessAccountId()).isEqualTo(businessAccountId);
        assertThat(invoice.getExternalSourceRef()).isNotBlank();
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void submitInvoiceRejectsUnknownBusinessAccount() {
        UUID businessAccountId = UUID.randomUUID();
        when(ledgerAccountRepository.findById(businessAccountId)).thenReturn(Optional.empty());
        CreateInvoiceCommand command = new CreateInvoiceCommand(businessAccountId, "customer-1",
                new BigDecimal("1000.00"), "USD", Instant.now().plus(30, ChronoUnit.DAYS));

        assertThatThrownBy(() -> invoiceService.submitInvoice(command))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void financeInvoiceDisbursesEightyPercentAndTwoPercentFeeThroughTheSettlementEngine() {
        UUID invoiceId = UUID.randomUUID();
        UUID businessAccountId = UUID.randomUUID();
        stubCleanInvoiceLookup(invoiceId, businessAccountId);
        Invoice claimed = issuedInvoice(invoiceId, businessAccountId);
        claimed.transitionTo(InvoiceStatus.FINANCED);
        when(invoiceTransactions.claimForFinancing(invoiceId)).thenReturn(claimed);

        UUID idempotencyKey = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        SettlementResult settlementResult = new SettlementResult(settlementId, platformAccountId, businessAccountId,
                new BigDecimal("800.00"), "USD", SettlementStatus.CONFIRMED, null, Instant.now(), Instant.now());
        when(settlementService.createSettlement(eq(idempotencyKey), any())).thenReturn(settlementResult);

        Advance expectedAdvance = new Advance(UUID.randomUUID(), invoiceId, new BigDecimal("800.00"),
                new BigDecimal("20.00"), settlementId);
        when(invoiceTransactions.recordAdvance(invoiceId, new BigDecimal("800.00"), new BigDecimal("20.00"), settlementId))
                .thenReturn(expectedAdvance);

        Advance result = invoiceService.financeInvoice(invoiceId, idempotencyKey);

        assertThat(result).isEqualTo(expectedAdvance);

        ArgumentCaptor<CreateSettlementCommand> commandCaptor = ArgumentCaptor.forClass(CreateSettlementCommand.class);
        verify(settlementService).createSettlement(eq(idempotencyKey), commandCaptor.capture());
        assertThat(commandCaptor.getValue().sourceAccountId()).isEqualTo(platformAccountId);
        assertThat(commandCaptor.getValue().destinationAccountId()).isEqualTo(businessAccountId);
        assertThat(commandCaptor.getValue().amount()).isEqualByComparingTo("800.00");
        assertThat(commandCaptor.getValue().currency()).isEqualTo("USD");

        verify(invoiceTransactions).recordAdvance(invoiceId, new BigDecimal("800.00"), new BigDecimal("20.00"), settlementId);
    }

    @Test
    void financeInvoicePropagatesClaimFailureWithoutTouchingTheSettlementEngine() {
        UUID invoiceId = UUID.randomUUID();
        UUID businessAccountId = UUID.randomUUID();
        stubCleanInvoiceLookup(invoiceId, businessAccountId);
        when(invoiceTransactions.claimForFinancing(invoiceId))
                .thenThrow(new InvoiceTransitionException(InvoiceStatus.FINANCED, InvoiceStatus.FINANCED));

        assertThatThrownBy(() -> invoiceService.financeInvoice(invoiceId, UUID.randomUUID()))
                .isInstanceOf(InvoiceTransitionException.class);

        verify(settlementService, org.mockito.Mockito.never()).createSettlement(any(), any());
    }

    @Test
    void financeInvoiceBlocksWhenFraudCheckReturnsBlockAndNeverTouchesTheSettlementEngine() {
        UUID invoiceId = UUID.randomUUID();
        UUID businessAccountId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(issuedInvoice(invoiceId, businessAccountId)));
        when(ledgerAccountRepository.findById(businessAccountId)).thenReturn(
                Optional.of(new LedgerAccount(businessAccountId, UUID.randomUUID(), new BigDecimal("0.00"), "USD")));
        when(fraudDetectionClient.check(any())).thenReturn(
                new FraudCheckResult(new BigDecimal("0.9"), FraudDecision.BLOCK, List.of("duplicate_customer_reference")));

        assertThatThrownBy(() -> invoiceService.financeInvoice(invoiceId, UUID.randomUUID()))
                .isInstanceOf(FraudBlockedException.class);

        verify(invoiceTransactions, org.mockito.Mockito.never()).claimForFinancing(any());
        verify(settlementService, org.mockito.Mockito.never()).createSettlement(any(), any());

        ArgumentCaptor<FraudAssessment> assessmentCaptor = ArgumentCaptor.forClass(FraudAssessment.class);
        verify(fraudAssessmentRepository).save(assessmentCaptor.capture());
        assertThat(assessmentCaptor.getValue().getDecision()).isEqualTo(FraudDecision.BLOCK);
        assertThat(assessmentCaptor.getValue().getInvoiceId()).isEqualTo(invoiceId);
        assertThat(assessmentCaptor.getValue().getReasons()).isEqualTo("duplicate_customer_reference");
    }

    @Test
    void financeInvoicePersistsAnAllowAssessmentAndSendsComputedFeaturesToTheFraudCheck() {
        UUID invoiceId = UUID.randomUUID();
        UUID businessAccountId = UUID.randomUUID();
        stubCleanInvoiceLookup(invoiceId, businessAccountId);
        when(invoiceRepository.countByCustomerReferenceAndBusinessAccountIdNot("customer-1", businessAccountId))
                .thenReturn(2L);
        when(invoiceRepository.countByBusinessAccountIdAndStatusIn(eq(businessAccountId), any()))
                .thenReturn(3L);
        Invoice claimed = issuedInvoice(invoiceId, businessAccountId);
        claimed.transitionTo(InvoiceStatus.FINANCED);
        when(invoiceTransactions.claimForFinancing(invoiceId)).thenReturn(claimed);
        UUID idempotencyKey = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        SettlementResult settlementResult = new SettlementResult(settlementId, platformAccountId, businessAccountId,
                new BigDecimal("800.00"), "USD", SettlementStatus.CONFIRMED, null, Instant.now(), Instant.now());
        when(settlementService.createSettlement(eq(idempotencyKey), any())).thenReturn(settlementResult);
        when(invoiceTransactions.recordAdvance(invoiceId, new BigDecimal("800.00"), new BigDecimal("20.00"), settlementId))
                .thenReturn(new Advance(UUID.randomUUID(), invoiceId, new BigDecimal("800.00"), new BigDecimal("20.00"), settlementId));

        invoiceService.financeInvoice(invoiceId, idempotencyKey);

        ArgumentCaptor<FraudCheckRequest> requestCaptor = ArgumentCaptor.forClass(FraudCheckRequest.class);
        verify(fraudDetectionClient).check(requestCaptor.capture());
        assertThat(requestCaptor.getValue().invoiceId()).isEqualTo(invoiceId);
        assertThat(requestCaptor.getValue().businessAccountId()).isEqualTo(businessAccountId);
        assertThat(requestCaptor.getValue().requestedAdvanceAmount()).isEqualByComparingTo("800.00");
        assertThat(requestCaptor.getValue().duplicateCustomerReferenceCount()).isEqualTo(2L);
        assertThat(requestCaptor.getValue().outstandingAdvanceCount()).isEqualTo(3L);

        ArgumentCaptor<FraudAssessment> assessmentCaptor = ArgumentCaptor.forClass(FraudAssessment.class);
        verify(fraudAssessmentRepository).save(assessmentCaptor.capture());
        assertThat(assessmentCaptor.getValue().getDecision()).isEqualTo(FraudDecision.ALLOW);
    }
}
