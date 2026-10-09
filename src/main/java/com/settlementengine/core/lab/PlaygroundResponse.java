package com.settlementengine.core.lab;

import com.settlementengine.core.service.SettlementResult;

import java.util.List;
import java.util.UUID;

/**
 * {@code httpStatus} is what the real {@code POST /settlements} would have answered; the lab wraps it
 * in a 200 envelope so the steps are still shown for 409/422 outcomes.
 */
public record PlaygroundResponse(
        UUID idempotencyKey,
        boolean replay,
        int httpStatus,
        SettlementResult result,
        String error,
        String fault,
        UUID settlementId,
        boolean stranded,
        String strandedExplanation,
        boolean externalRecordHeld,
        String orphanedExternalRef,
        List<LabStep> steps,
        String requestId) {
}
