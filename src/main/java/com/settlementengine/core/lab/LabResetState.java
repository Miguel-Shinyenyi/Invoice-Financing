package com.settlementengine.core.lab;

import java.time.Instant;

public record LabResetState(Instant lastResetAt, Instant nextResetAt) {
}
