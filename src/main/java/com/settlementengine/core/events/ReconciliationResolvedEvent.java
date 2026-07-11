package com.settlementengine.core.events;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationResolvedEvent(
        UUID settlementId,
        String resolution,
        Instant occurredAt) {
}
