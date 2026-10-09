package com.settlementengine.core.invoicing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceTest {

    private Invoice newInvoice() {
        return new Invoice(UUID.randomUUID(), UUID.randomUUID(), "customer-123", new BigDecimal("1000.00"),
                "USD", Instant.now().plus(30, ChronoUnit.DAYS), "INV-ref-1");
    }

    @Test
    void startsIssued() {
        Invoice invoice = newInvoice();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    void allowsValidTransition() {
        Invoice invoice = newInvoice();

        invoice.transitionTo(InvoiceStatus.FINANCED);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.FINANCED);
    }

    @Test
    void rejectsInvalidTransition() {
        Invoice invoice = newInvoice();

        assertThatThrownBy(() -> invoice.transitionTo(InvoiceStatus.REPAID))
                .isInstanceOf(InvoiceTransitionException.class);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    void overdueCanStillBeRepaidLate() {
        Invoice invoice = newInvoice();
        invoice.transitionTo(InvoiceStatus.FINANCED);
        invoice.transitionTo(InvoiceStatus.OVERDUE);

        invoice.transitionTo(InvoiceStatus.REPAID);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REPAID);
    }
}
