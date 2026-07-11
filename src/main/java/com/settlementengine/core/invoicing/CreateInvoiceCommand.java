package com.settlementengine.core.invoicing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateInvoiceCommand(
        UUID businessAccountId,
        String customerReference,
        BigDecimal amount,
        String currency,
        Instant dueDate) {
}
