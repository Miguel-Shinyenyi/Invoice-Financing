package com.settlementengine.core.lab;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LabLoadStatsTest {

    @Test
    void percentilesAreMeasuredFromTheRecordedLatenciesNotInvented() {
        LabLoadStats stats = new LabLoadStats();
        for (int ms = 1; ms <= 100; ms++) {
            stats.record(201, "CONFIRMED", TimeUnit.MILLISECONDS.toNanos(ms));
        }
        LabLoadStats.Latency latency = stats.latency();
        assertThat(latency.p50Ms()).isCloseTo(50.0, within(3.0));
        assertThat(latency.p95Ms()).isCloseTo(95.0, within(3.0));
        assertThat(latency.p99Ms()).isCloseTo(99.0, within(3.0));
    }

    @Test
    void noRequestsMeansNoPercentilesRatherThanZeroesPretendingToBeData() {
        LabLoadStats.Latency latency = new LabLoadStats().latency();
        assertThat(latency.p50Ms()).isNull();
        assertThat(latency.p95Ms()).isNull();
        assertThat(latency.p99Ms()).isNull();
    }

    @Test
    void countsByStatusAndByOutcome() {
        LabLoadStats stats = new LabLoadStats();
        stats.record(201, "CONFIRMED", 1_000_000);
        stats.record(201, "CONFIRMED", 1_000_000);
        stats.record(201, "UNKNOWN", 1_000_000);
        stats.record(409, "409", 1_000_000);
        stats.record(422, "422", 1_000_000);
        assertThat(stats.statusCounts()).containsEntry("201", 3L).containsEntry("409", 1L).containsEntry("422", 1L);
        assertThat(stats.outcomeCounts()).containsEntry("CONFIRMED", 2L).containsEntry("UNKNOWN", 1L);
        assertThat(stats.total()).isEqualTo(5);
    }

    @Test
    void aTransportFailureIsCountedAsItsOwnStatusNotSwallowed() {
        LabLoadStats stats = new LabLoadStats();
        stats.recordTransportFailure(2_000_000);
        assertThat(stats.statusCounts()).containsEntry("ERR", 1L);
        assertThat(stats.total()).isEqualTo(1);
    }

    @Test
    void throughputBetweenTwoSamplesIsTheCompletedDeltaOverTheElapsedTime() {
        LabLoadStats stats = new LabLoadStats();
        for (int i = 0; i < 30; i++) {
            stats.record(201, "CONFIRMED", 1_000_000);
        }
        assertThat(LabLoadStats.rate(0, stats.total(), 1.5)).isEqualTo(20.0);
        assertThat(LabLoadStats.rate(10, 10, 0)).isZero();
    }
}
