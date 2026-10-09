package com.settlementengine.core.invoicing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MockInvoicePaymentSourceTest {

    private final MockInvoicePaymentSource source = new MockInvoicePaymentSource();

    @Test
    void unknownReferenceIsNotFound() {
        assertThat(source.checkPayment("never-marked")).isEmpty();
    }

    @Test
    void markPaidMakesItFindable() {
        source.markPaid("INV-ref-1", new BigDecimal("500.00"));

        assertThat(source.checkPayment("INV-ref-1")).contains(new BigDecimal("500.00"));
    }
}
