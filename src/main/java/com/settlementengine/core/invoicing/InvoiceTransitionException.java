package com.settlementengine.core.invoicing;

public class InvoiceTransitionException extends RuntimeException {

    public InvoiceTransitionException(InvoiceStatus from, InvoiceStatus to) {
        super("Cannot transition invoice from %s to %s".formatted(from, to));
    }
}
