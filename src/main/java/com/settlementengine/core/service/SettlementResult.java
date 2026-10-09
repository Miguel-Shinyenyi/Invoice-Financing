package com.settlementengine.core.service;

import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SettlementResult(
        UUID settlementId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        SettlementStatus status,
        String externalRef,
        Instant createdAt,
        Instant updatedAt) {

    public static SettlementResult from(Settlement settlement) {
        return new SettlementResult(
                settlement.getId(),
                settlement.getSourceAccountId(),
                settlement.getDestinationAccountId(),
                settlement.getAmount(),
                settlement.getCurrency(),
                settlement.getStatus(),
                settlement.getExternalRef(),
                settlement.getCreatedAt(),
                settlement.getUpdatedAt());
    }
}
