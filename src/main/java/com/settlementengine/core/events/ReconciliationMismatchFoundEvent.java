package com.settlementengine.core.events;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationMismatchFoundEvent(
        UUID mismatchId,
        UUID runId,
        UUID settlementId,
        String internalState,
        String externalState,
        Instant occurredAt) {
}
