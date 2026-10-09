package com.settlementengine.core.lab;

import java.time.Instant;

/** One ordered step of what the engine did, assembled from real rows and log events. */
public record LabStep(int order, String key, String title, Instant at, String detail, String source) {
}
