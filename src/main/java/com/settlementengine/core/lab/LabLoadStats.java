package com.settlementengine.core.lab;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-run measurements. Latency percentiles come from a Micrometer {@link Timer} histogram fed with every
 * real request's measured duration. Nothing here is estimated.
 */
public class LabLoadStats {

    public record Latency(Double p50Ms, Double p95Ms, Double p99Ms, Double maxMs) {
    }

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Timer timer = Timer.builder("lab.load.request")
            .publishPercentiles(0.5, 0.95, 0.99)
            .percentilePrecision(3)
            .minimumExpectedValue(java.time.Duration.ofNanos(100_000))
            .maximumExpectedValue(java.time.Duration.ofSeconds(60))
            .register(registry);
    private final Map<String, LongAdder> status = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> outcome = new ConcurrentHashMap<>();
    private final LongAdder total = new LongAdder();

    public void record(int httpStatus, String outcomeLabel, long latencyNanos) {
        timer.record(latencyNanos, TimeUnit.NANOSECONDS);
        status.computeIfAbsent(Integer.toString(httpStatus), k -> new LongAdder()).increment();
        if (outcomeLabel != null) {
            outcome.computeIfAbsent(outcomeLabel, k -> new LongAdder()).increment();
        }
        total.increment();
    }

    public void recordTransportFailure(long latencyNanos) {
        timer.record(latencyNanos, TimeUnit.NANOSECONDS);
        status.computeIfAbsent("ERR", k -> new LongAdder()).increment();
        total.increment();
    }

    public long total() {
        return total.sum();
    }

    public Map<String, Long> statusCounts() {
        return snapshot(status);
    }

    public Map<String, Long> outcomeCounts() {
        return snapshot(outcome);
    }

    public Latency latency() {
        if (timer.count() == 0) {
            return new Latency(null, null, null, null);
        }
        HistogramSnapshot snap = timer.takeSnapshot();
        Double p50 = null;
        Double p95 = null;
        Double p99 = null;
        for (ValueAtPercentile v : snap.percentileValues()) {
            double ms = v.value(TimeUnit.MILLISECONDS);
            if (v.percentile() == 0.5) {
                p50 = ms;
            } else if (v.percentile() == 0.95) {
                p95 = ms;
            } else if (v.percentile() == 0.99) {
                p99 = ms;
            }
        }
        return new Latency(p50, p95, p99, snap.max(TimeUnit.MILLISECONDS));
    }

    /** Requests per second between two cumulative totals. */
    public static double rate(long previousTotal, long currentTotal, double elapsedSeconds) {
        return elapsedSeconds <= 0 ? 0.0 : (currentTotal - previousTotal) / elapsedSeconds;
    }

    private static Map<String, Long> snapshot(Map<String, LongAdder> source) {
        Map<String, Long> out = new TreeMap<>();
        source.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
