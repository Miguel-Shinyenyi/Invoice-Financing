package com.settlementengine.core.lab;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Global cap of one load run at a time, with a cooldown between runs. */
public class LoadRunGate {

    public enum Decision { STARTED, BUSY, COOLING_DOWN }

    private final long cooldownNanos;
    private final LongSupplier nanoClock;
    private String activeRunId;
    private Long finishedAtNanos;

    public LoadRunGate(Duration cooldown, LongSupplier nanoClock) {
        this.cooldownNanos = cooldown.toNanos();
        this.nanoClock = nanoClock;
    }

    public synchronized Decision tryStart(String runId) {
        if (activeRunId != null) {
            return Decision.BUSY;
        }
        if (finishedAtNanos != null && nanoClock.getAsLong() - finishedAtNanos < cooldownNanos) {
            return Decision.COOLING_DOWN;
        }
        activeRunId = runId;
        return Decision.STARTED;
    }

    public synchronized void finish(String runId) {
        if (runId.equals(activeRunId)) {
            activeRunId = null;
            finishedAtNanos = nanoClock.getAsLong();
        }
    }

    public synchronized String activeRunId() {
        return activeRunId;
    }
}
