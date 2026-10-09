package com.settlementengine.core.api;

import com.settlementengine.core.reconciliation.LedgerMismatch;
import com.settlementengine.core.reconciliation.MismatchResolutionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerMismatchResponse(
        UUID id,
        UUID accountId,
        BigDecimal storedBalance,
        BigDecimal computedBalance,
        String details,
        MismatchResolutionStatus resolutionStatus,
        Instant resolvedAt,
        Instant createdAt) {

    public static LedgerMismatchResponse from(LedgerMismatch mismatch) {
        return new LedgerMismatchResponse(mismatch.getId(), mismatch.getAccountId(), mismatch.getStoredBalance(),
                mismatch.getComputedBalance(), mismatch.getDetails(), mismatch.getResolutionStatus(),
                mismatch.getResolvedAt(), mismatch.getCreatedAt());
    }
}
