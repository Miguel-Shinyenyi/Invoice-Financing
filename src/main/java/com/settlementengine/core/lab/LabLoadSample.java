package com.settlementengine.core.lab;

import java.time.Instant;
import java.util.Map;

/** One per-second observation of a running load plan. Every number is measured. */
public record LabLoadSample(
        int second,
        Instant at,
        long totalRequests,
        double requestsPerSecond,
        Double p50Ms,
        Double p95Ms,
        Double p99Ms,
        Map<String, Long> statusCounts,
        Map<String, Long> outcomeCounts,
        long finalizeRetries,
        long unknownFallbacks,
        int hikariActive,
        int hikariIdle,
        int hikariPending) {
}
