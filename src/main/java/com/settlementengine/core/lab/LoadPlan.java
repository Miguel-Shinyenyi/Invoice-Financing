package com.settlementengine.core.lab;

import java.math.BigDecimal;

/** A load run request. Always validated by {@link LoadPlanValidator} against server-side caps. */
public record LoadPlan(
        LoadScenario scenario,
        int virtualUsers,
        int durationSeconds,
        int totalRequests,
        BigDecimal amount,
        FaultSpec fault,
        Long seed) {

    /** Per-call fault probabilities; the rest of the calls are fault free. */
    public record FaultSpec(double requestLost, double responseLost, double declined, double slow, int slowMs) {
        double total() {
            return requestLost + responseLost + declined + slow;
        }
    }
}
