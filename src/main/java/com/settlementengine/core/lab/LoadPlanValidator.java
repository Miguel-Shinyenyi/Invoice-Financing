package com.settlementengine.core.lab;

/** Server-side validation of every load parameter. The client's idea of the caps is never trusted. */
public final class LoadPlanValidator {

    private LoadPlanValidator() {
    }

    public static void validate(LoadPlan plan, LabProperties.Load caps) {
        if (plan == null || plan.scenario() == null) {
            throw new LabValidationException("scenario is required");
        }
        requireRange("virtualUsers", plan.virtualUsers(), 1, caps.maxVirtualUsers());
        requireRange("durationSeconds", plan.durationSeconds(), 1, caps.maxDurationSeconds());
        requireRange("totalRequests", plan.totalRequests(), 1, caps.maxTotalRequests());
        if (plan.amount() == null) {
            throw new LabValidationException("amount is required");
        }
        if (plan.amount().scale() > 2 || plan.amount().compareTo(caps.minAmount()) < 0
                || plan.amount().compareTo(caps.maxAmount()) > 0) {
            throw new LabValidationException("amount must be between " + caps.minAmount() + " and "
                    + caps.maxAmount() + " with at most 2 decimals");
        }
        LoadPlan.FaultSpec fault = plan.fault();
        if (fault != null) {
            for (double p : new double[] {fault.requestLost(), fault.responseLost(), fault.declined(), fault.slow()}) {
                if (p < 0 || p > 1 || Double.isNaN(p)) {
                    throw new LabValidationException("fault probabilities must be between 0 and 1");
                }
            }
            if (fault.total() > 1.0 + 1e-9) {
                throw new LabValidationException("fault probabilities must sum to at most 1");
            }
            if (fault.slowMs() < 0 || fault.slowMs() > LabFaultPlan.MAX_SLOW_MS) {
                throw new LabValidationException("slowMs must be between 0 and " + LabFaultPlan.MAX_SLOW_MS);
            }
        }
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new LabValidationException(name + " must be between " + min + " and " + max);
        }
    }
}
