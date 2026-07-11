package com.settlementengine.core.domain;

public enum SettlementStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    UNKNOWN,
    REVERSED;

    public boolean canTransitionTo(SettlementStatus target) {
        return switch (this) {
            case PENDING -> target == CONFIRMED || target == FAILED || target == UNKNOWN;
            case UNKNOWN -> target == CONFIRMED || target == FAILED;
            case CONFIRMED -> target == REVERSED;
            case FAILED, REVERSED -> false;
        };
    }
}
