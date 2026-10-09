package com.settlementengine.core.api;

import com.settlementengine.core.reconciliation.ReconciliationRun;
import com.settlementengine.core.reconciliation.ReconciliationRunStatus;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRunResponse(
        UUID id,
        Instant startedAt,
        Instant finishedAt,
        int recordsChecked,
        int mismatchesFound,
        ReconciliationRunStatus status) {

    public static ReconciliationRunResponse from(ReconciliationRun run) {
        return new ReconciliationRunResponse(run.getId(), run.getStartedAt(), run.getFinishedAt(),
                run.getRecordsChecked(), run.getMismatchesFound(), run.getStatus());
    }
}
