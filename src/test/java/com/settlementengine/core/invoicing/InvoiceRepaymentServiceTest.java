package com.settlementengine.core.invoicing;

import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.repository.AdvanceRepository;
import com.settlementengine.core.repository.InvoiceRepository;
import com.settlementengine.core.service.CreateSettlementCommand;
import com.settlementengine.core.service.SettlementResult;
import com.settlementengine.core.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvoiceRepaymentServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private AdvanceRepository advanceRepository;
    @Mock
    private InvoicePaymentSource invoicePaymentSource;
    @Mock
    private SettlementService settlementService;
    @Mock
    private InvoiceTransactions invoiceTransactions;

    private InvoiceRepaymentService invoiceRepaymentService;
    private final UUID platformAccountId = UUID.randomUUID();
    private static final long GRACE_PERIOD_DAYS = 3;

    @BeforeEach
    void setUp() {
        invoiceRepaymentService = new InvoiceRepaymentService(invoiceRepository, advanceRepository,
                invoicePaymentSource, settlementService, invoiceTransactions, platformAccountId, GRACE_PERIOD_DAYS);
    }

    private Invoice financedInvoice(UUID businessAccountId, BigDecimal amount, Instant dueDate, String externalRef) {
        Invoice invoice = new Invoice(UUID.randomUUID(), businessAccountId, "customer-1", amount, "USD", dueDate, externalRef);
        invoice.transitionTo(InvoiceStatus.FINANCED);
        return invoice;
    }

    @Test
    void noCandidatesDoesNothing() {
        when(invoiceRepository.findByStatusIn(any())).thenReturn(List.of());

        invoiceRepaymentService.checkRepayments();

        verify(settlementService, never()).createSettlement(any(), any());
    }

    @Test
    void matchedPaymentCollectsRepaymentAndMarksInvoiceRepaid() {
        UUID businessAccountId = UUID.randomUUID();
        Invoice invoice = financedInvoice(businessAccountId, new BigDecimal("1000.00"),
                Instant.now().plus(10, ChronoUnit.DAYS), "INV-ref-1");
        Advance advance = new Advance(UUID.randomUUID(), invoice.getId(), new BigDecimal("800.00"),
                new BigDecimal("20.00"), UUID.randomUUID());
        when(invoiceRepository.findByStatusIn(any())).thenReturn(List.of(invoice));
        when(invoicePaymentSource.checkPayment("INV-ref-1")).thenReturn(Optional.of(new BigDecimal("1000.00")));
        when(advanceRepository.findByInvoiceId(invoice.getId())).thenReturn(Optional.of(advance));
        UUID repaymentSettlementId = UUID.randomUUID();
        when(settlementService.createSettlement(any(), any())).thenReturn(
                new SettlementResult(repaymentSettlementId, businessAccountId, platformAccountId,
                        new BigDecimal("820.00"), "USD", SettlementStatus.CONFIRMED, null, Instant.now(), Instant.now()));

        invoiceRepaymentService.checkRepayments();

        ArgumentCaptor<CreateSettlementCommand> commandCaptor = ArgumentCaptor.forClass(CreateSettlementCommand.class);
        verify(settlementService).createSettlement(any(), commandCaptor.capture());
        assertThat(commandCaptor.getValue().sourceAccountId()).isEqualTo(businessAccountId);
        assertThat(commandCaptor.getValue().destinationAccountId()).isEqualTo(platformAccountId);
        assertThat(commandCaptor.getValue().amount()).isEqualByComparingTo("820.00");

        verify(invoiceTransactions).recordRepayment(invoice.getId(), advance, repaymentSettlementId);
    }

    @Test
    void repaymentIdempotencyKeyIsDeterministicAcrossRuns() {
        UUID businessAccountId = UUID.randomUUID();
        Invoice invoice = financedInvoice(businessAccountId, new BigDecimal("1000.00"),
                Instant.now().plus(10, ChronoUnit.DAYS), "INV-ref-2");
        Advance advance = new Advance(UUID.randomUUID(), invoice.getId(), new BigDecimal("800.00"),
                new BigDecimal("20.00"), UUID.randomUUID());
        when(invoiceRepository.findByStatusIn(any())).thenReturn(List.of(invoice));
        when(invoicePaymentSource.checkPayment("INV-ref-2")).thenReturn(Optional.of(new BigDecimal("1000.00")));
        when(advanceRepository.findByInvoiceId(invoice.getId())).thenReturn(Optional.of(advance));
        when(settlementService.createSettlement(any(), any())).thenReturn(
                new SettlementResult(UUID.randomUUID(), businessAccountId, platformAccountId,
                        new BigDecimal("820.00"), "USD", SettlementStatus.UNKNOWN, null, Instant.now(), Instant.now()));

        invoiceRepaymentService.checkRepayments();
        invoiceRepaymentService.checkRepayments();

        ArgumentCaptor<UUID> keyCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(settlementService, times(2)).createSettlement(keyCaptor.capture(), any());
        assertThat(keyCaptor.getAllValues().get(0)).isEqualTo(keyCaptor.getAllValues().get(1));
    }

    @Test
    void mismatchedPaymentAmountIsNotAutoResolved() {
        UUID businessAccountId = UUID.randomUUID();
        Invoice invoice = financedInvoice(businessAccountId, new BigDecimal("1000.00"),
                Instant.now().plus(10, ChronoUnit.DAYS), "INV-ref-3");
        when(invoiceRepository.findByStatusIn(any())).thenReturn(List.of(invoice));
        when(invoicePaymentSource.checkPayment("INV-ref-3")).thenReturn(Optional.of(new BigDecimal("500.00")));

        invoiceRepaymentService.checkRepayments();

        verify(settlementService, never()).createSettlement(any(), any());
        verify(invoiceTransactions, never()).recordRepayment(any(), any(), any());
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.FINANCED);
    }

    @Test
    void noPaymentWithinGracePeriodTakesNoAction() {
        Invoice invoice = financedInvoice(UUID.randomUUID(), new BigDecimal("1000.00"),
                Instant.now().minus(1, ChronoUnit.DAYS), "INV-ref-4");
        when(invoiceRepository.findByStatusIn(any())).thenReturn(List.of(invoice));
        when(invoicePaymentSource.checkPayment("INV-ref-4")).thenReturn(Optional.empty());

        invoiceRepaymentService.checkRepayments();

        verify(invoiceTransactions, never()).markOverdue(any());
    }

    @Test
    void noPaymentPastGracePeriodMarksOverdue() {
        Invoice invoice = financedInvoice(UUID.randomUUID(), new BigDecimal("1000.00"),
                Instant.now().minus(GRACE_PERIOD_DAYS + 1, ChronoUnit.DAYS), "INV-ref-5");
        when(invoiceRepository.findByStatusIn(any())).thenReturn(List.of(invoice));
        when(invoicePaymentSource.checkPayment("INV-ref-5")).thenReturn(Optional.empty());

        invoiceRepaymentService.checkRepayments();

        verify(invoiceTransactions).markOverdue(invoice.getId());
    }

    @Test
    void alreadyOverdueInvoiceIsNotMarkedOverdueAgain() {
        Invoice invoice = financedInvoice(UUID.randomUUID(), new BigDecimal("1000.00"),
                Instant.now().minus(GRACE_PERIOD_DAYS + 1, ChronoUnit.DAYS), "INV-ref-6");
        invoice.transitionTo(InvoiceStatus.OVERDUE);
        when(invoiceRepository.findByStatusIn(any())).thenReturn(List.of(invoice));
        when(invoicePaymentSource.checkPayment("INV-ref-6")).thenReturn(Optional.empty());

        invoiceRepaymentService.checkRepayments();

        verify(invoiceTransactions, never()).markOverdue(any());
    }

    @Test
    void oneInvoiceErroringDoesNotStopProcessingOthers() {
        Invoice broken = financedInvoice(UUID.randomUUID(), new BigDecimal("1000.00"),
                Instant.now().plus(10, ChronoUnit.DAYS), "INV-ref-broken");
        Invoice healthy = financedInvoice(UUID.randomUUID(), new BigDecimal("500.00"),
                Instant.now().minus(GRACE_PERIOD_DAYS + 1, ChronoUnit.DAYS), "INV-ref-healthy");
        when(invoiceRepository.findByStatusIn(any())).thenReturn(List.of(broken, healthy));
        when(invoicePaymentSource.checkPayment("INV-ref-broken")).thenThrow(new RuntimeException("boom"));
        when(invoicePaymentSource.checkPayment("INV-ref-healthy")).thenReturn(Optional.empty());

        invoiceRepaymentService.checkRepayments();

        verify(invoiceTransactions).markOverdue(healthy.getId());
    }
}
