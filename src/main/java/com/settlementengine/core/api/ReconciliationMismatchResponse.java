package com.settlementengine.core.api;

import com.settlementengine.core.reconciliation.MismatchResolutionStatus;
import com.settlementengine.core.reconciliation.ReconciliationMismatch;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationMismatchResponse(
        UUID id,
        UUID runId,
        UUID settlementId,
        String internalState,
        String externalState,
        String details,
        MismatchResolutionStatus resolutionStatus,
        Instant resolvedAt,
        Instant createdAt) {

    public static ReconciliationMismatchResponse from(ReconciliationMismatch mismatch) {
        return new ReconciliationMismatchResponse(mismatch.getId(), mismatch.getRunId(), mismatch.getSettlementId(),
                mismatch.getInternalState(), mismatch.getExternalState(), mismatch.getDetails(),
                mismatch.getResolutionStatus(), mismatch.getResolvedAt(), mismatch.getCreatedAt());
    }
}
