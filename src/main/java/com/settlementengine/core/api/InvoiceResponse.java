package com.settlementengine.core.api;

import com.settlementengine.core.invoicing.Advance;
import com.settlementengine.core.invoicing.Invoice;
import com.settlementengine.core.invoicing.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID businessAccountId,
        String customerReference,
        BigDecimal amount,
        String currency,
        Instant dueDate,
        InvoiceStatus status,
        String externalSourceRef,
        Instant createdAt,
        Instant updatedAt,
        AdvanceResponse advance) {

    public static InvoiceResponse from(Invoice invoice, Advance advance) {
        return new InvoiceResponse(invoice.getId(), invoice.getBusinessAccountId(), invoice.getCustomerReference(),
                invoice.getAmount(), invoice.getCurrency(), invoice.getDueDate(), invoice.getStatus(),
                invoice.getExternalSourceRef(), invoice.getCreatedAt(), invoice.getUpdatedAt(),
                advance == null ? null : AdvanceResponse.from(advance));
    }
}
