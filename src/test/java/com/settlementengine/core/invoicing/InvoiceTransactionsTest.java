package com.settlementengine.core.invoicing;

import com.settlementengine.core.repository.AdvanceRepository;
import com.settlementengine.core.repository.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceTransactionsTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private AdvanceRepository advanceRepository;

    private InvoiceTransactions invoiceTransactions;

    @BeforeEach
    void setUp() {
        invoiceTransactions = new InvoiceTransactions(invoiceRepository, advanceRepository);
    }

    private Invoice issuedInvoice() {
        return new Invoice(UUID.randomUUID(), UUID.randomUUID(), "customer-1", new BigDecimal("1000.00"),
                "USD", Instant.now().plus(30, ChronoUnit.DAYS), "INV-ref-1");
    }

    @Test
    void claimForFinancingTransitionsIssuedInvoiceToFinanced() {
        Invoice invoice = issuedInvoice();
        when(invoiceRepository.findByIdForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));

        Invoice claimed = invoiceTransactions.claimForFinancing(invoice.getId());

        assertThat(claimed.getStatus()).isEqualTo(InvoiceStatus.FINANCED);
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void claimForFinancingRejectsAlreadyFinancedInvoice() {
        Invoice invoice = issuedInvoice();
        invoice.transitionTo(InvoiceStatus.FINANCED);
        when(invoiceRepository.findByIdForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> invoiceTransactions.claimForFinancing(invoice.getId()))
                .isInstanceOf(InvoiceTransitionException.class);
    }

    @Test
    void claimForFinancingThrowsWhenInvoiceNotFound() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceTransactions.claimForFinancing(invoiceId))
                .isInstanceOf(InvoiceNotFoundException.class);
    }

    @Test
    void recordAdvanceSavesANewDisbursedAdvance() {
        UUID invoiceId = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();

        Advance advance = invoiceTransactions.recordAdvance(invoiceId, new BigDecimal("800.00"),
                new BigDecimal("20.00"), settlementId);

        assertThat(advance.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(advance.getAmountAdvanced()).isEqualByComparingTo("800.00");
        assertThat(advance.getFee()).isEqualByComparingTo("20.00");
        assertThat(advance.getDisbursedSettlementId()).isEqualTo(settlementId);
        assertThat(advance.getStatus()).isEqualTo(AdvanceStatus.DISBURSED);

        ArgumentCaptor<Advance> captor = ArgumentCaptor.forClass(Advance.class);
        verify(advanceRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(advance);
    }

    @Test
    void recordRepaymentTransitionsInvoiceAndAdvance() {
        Invoice invoice = issuedInvoice();
        invoice.transitionTo(InvoiceStatus.FINANCED);
        Advance advance = new Advance(UUID.randomUUID(), invoice.getId(), new BigDecimal("800.00"),
                new BigDecimal("20.00"), UUID.randomUUID());
        UUID repaidSettlementId = UUID.randomUUID();
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        invoiceTransactions.recordRepayment(invoice.getId(), advance, repaidSettlementId);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REPAID);
        assertThat(advance.getStatus()).isEqualTo(AdvanceStatus.REPAID);
        assertThat(advance.getRepaidSettlementId()).isEqualTo(repaidSettlementId);
        verify(invoiceRepository).save(invoice);
        verify(advanceRepository).save(advance);
    }

    @Test
    void markOverdueTransitionsFinancedInvoice() {
        Invoice invoice = issuedInvoice();
        invoice.transitionTo(InvoiceStatus.FINANCED);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));

        invoiceTransactions.markOverdue(invoice.getId());

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
        verify(invoiceRepository).save(invoice);
    }
}
