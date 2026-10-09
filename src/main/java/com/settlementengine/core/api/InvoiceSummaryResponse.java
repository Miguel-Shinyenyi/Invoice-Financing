package com.settlementengine.core.api;

import com.settlementengine.core.invoicing.Invoice;
import com.settlementengine.core.invoicing.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InvoiceSummaryResponse(
        UUID id,
        UUID businessAccountId,
        String customerReference,
        BigDecimal amount,
        String currency,
        Instant dueDate,
        InvoiceStatus status,
        String externalSourceRef,
        Instant updatedAt) {

    public static InvoiceSummaryResponse from(Invoice invoice) {
        return new InvoiceSummaryResponse(
                invoice.getId(),
                invoice.getBusinessAccountId(),
                invoice.getCustomerReference(),
                invoice.getAmount(),
                invoice.getCurrency(),
                invoice.getDueDate(),
                invoice.getStatus(),
                invoice.getExternalSourceRef(),
                invoice.getUpdatedAt());
    }
}
