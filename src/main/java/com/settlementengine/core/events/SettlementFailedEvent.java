package com.settlementengine.core.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SettlementFailedEvent(
        UUID settlementId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {
}
