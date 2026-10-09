package com.settlementengine.core.lab;

import java.util.function.Supplier;

/**
 * Per-thread fault plan. A ThreadLocal, not a global flag, so one visitor's chaos never leaks into
 * another visitor's request.
 */
public final class LabFaultContext {

    private static final ThreadLocal<LabFaultPlan> CURRENT = new ThreadLocal<>();

    private LabFaultContext() {
    }

    public static void set(LabFaultPlan plan) {
        CURRENT.set(plan);
    }

    public static LabFaultPlan get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T with(LabFaultPlan plan, Supplier<T> action) {
        LabFaultPlan previous = CURRENT.get();
        CURRENT.set(plan);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
