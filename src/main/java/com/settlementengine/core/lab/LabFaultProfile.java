package com.settlementengine.core.lab;

import java.util.EnumMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Per-call probabilities for a load run, e.g. 5 percent RESPONSE_LOST. The remainder is NONE. */
public record LabFaultProfile(Map<LabFault, Double> probabilities, int slowMs) {

    public LabFaultProfile {
        EnumMap<LabFault, Double> copy = new EnumMap<>(LabFault.class);
        double total = 0;
        for (Map.Entry<LabFault, Double> e : probabilities.entrySet()) {
            if (e.getKey() == LabFault.NONE) {
                continue;
            }
            if (e.getValue() < 0) {
                throw new IllegalArgumentException("Negative probability for " + e.getKey());
            }
            total += e.getValue();
            copy.put(e.getKey(), e.getValue());
        }
        if (total > 1.0 + 1e-9) {
            throw new IllegalArgumentException("Fault probabilities sum to " + total + ", more than 1");
        }
        probabilities = Map.copyOf(copy);
        slowMs = Math.max(0, Math.min(LabFaultPlan.MAX_SLOW_MS, slowMs));
    }

    public LabFault pick(RandomGenerator rng) {
        double roll = rng.nextDouble();
        double cumulative = 0;
        for (LabFault fault : LabFault.values()) {
            Double p = probabilities.get(fault);
            if (p == null) {
                continue;
            }
            cumulative += p;
            if (roll < cumulative) {
                return fault;
            }
        }
        return LabFault.NONE;
    }
}
