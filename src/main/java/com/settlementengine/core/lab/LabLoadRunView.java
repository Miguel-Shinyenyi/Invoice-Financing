package com.settlementengine.core.lab;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The JSON shape of a load run, live or finished. Also what is downloadable and what history stores. */
public record LabLoadRunView(
        String runId,
        String status,
        LoadPlan plan,
        Instant startedAt,
        Instant finishedAt,
        List<LabLoadSample> samples,
        Summary summary,
        List<LabInvariant> invariants,
        String verdict,
        Stranded stranded,
        String k6Equivalent,
        String note) {

    public record Summary(long totalRequests, double durationSeconds, double averageRequestsPerSecond, Double p50Ms,
                          Double p95Ms, Double p99Ms, Double maxMs, Map<String, Long> statusCounts,
                          Map<String, Long> outcomeCounts, long finalizeRetries, long unknownFallbacks,
                          int peakHikariActive, int peakHikariPending) {
    }

    /** UNKNOWN outcomes from injected faults are expected: reported as a count with the explanation, not as a failure. */
    public record Stranded(long unknownOutcomes, String explanation) {
    }
}
