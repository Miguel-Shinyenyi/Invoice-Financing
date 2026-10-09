package com.settlementengine.core.api;

import com.settlementengine.core.readmodel.SettlementReadModel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SettlementSummaryResponse(
        UUID settlementId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String status,
        Instant updatedAt) {

    public static SettlementSummaryResponse from(SettlementReadModel row) {
        return new SettlementSummaryResponse(
                row.getSettlementId(),
                row.getSourceAccountId(),
                row.getDestinationAccountId(),
                row.getAmount(),
                row.getCurrency(),
                row.getStatus(),
                row.getUpdatedAt());
    }
}
