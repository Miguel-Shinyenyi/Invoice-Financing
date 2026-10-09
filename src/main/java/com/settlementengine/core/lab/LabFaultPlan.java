package com.settlementengine.core.lab;

import java.util.random.RandomGenerator;

/** What the gateway decorator should do on this call: one fixed fault, or a draw from a profile. */
public record LabFaultPlan(LabFault fault, int slowMs, LabFaultProfile profile) {

    public static final int MAX_SLOW_MS = 3000;

    public static LabFaultPlan of(LabFault fault) {
        return new LabFaultPlan(fault, fault == LabFault.SLOW ? 500 : 0, null);
    }

    public static LabFaultPlan slow(int ms) {
        return new LabFaultPlan(LabFault.SLOW, Math.max(0, Math.min(MAX_SLOW_MS, ms)), null);
    }

    public static LabFaultPlan profile(LabFaultProfile profile) {
        return new LabFaultPlan(LabFault.NONE, profile.slowMs(), profile);
    }

    LabFault resolve(RandomGenerator rng) {
        return profile != null ? profile.pick(rng) : fault;
    }
}
