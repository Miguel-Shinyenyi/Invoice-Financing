package com.settlementengine.core.domain;

public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(SettlementStatus from, SettlementStatus to) {
        super("Cannot transition settlement from %s to %s".formatted(from, to));
    }
}
