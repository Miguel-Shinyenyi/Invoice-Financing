package com.settlementengine.core.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SettlementConfirmedEvent(
        UUID settlementId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {
}
