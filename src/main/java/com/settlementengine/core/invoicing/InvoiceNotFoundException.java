package com.settlementengine.core.invoicing;

import java.util.UUID;

public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(UUID invoiceId) {
        super("No invoice with id " + invoiceId);
    }
}
