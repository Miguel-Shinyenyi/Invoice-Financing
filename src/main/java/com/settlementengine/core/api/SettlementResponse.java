package com.settlementengine.core.api;

import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;
import com.settlementengine.core.service.SettlementResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SettlementResponse(
        UUID settlementId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        SettlementStatus status,
        String externalRef,
        Instant createdAt,
        Instant updatedAt) {

    public static SettlementResponse from(SettlementResult result) {
        return new SettlementResponse(
                result.settlementId(),
                result.sourceAccountId(),
                result.destinationAccountId(),
                result.amount(),
                result.currency(),
                result.status(),
                result.externalRef(),
                result.createdAt(),
                result.updatedAt());
    }

    public static SettlementResponse from(Settlement settlement) {
        return from(SettlementResult.from(settlement));
    }
}
