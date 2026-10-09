package com.settlementengine.core.invoicing;

public enum AdvanceStatus {
    DISBURSED,
    REPAID,
    DEFAULTED;

    public boolean canTransitionTo(AdvanceStatus target) {
        return switch (this) {
            case DISBURSED -> target == REPAID || target == DEFAULTED;
            case REPAID, DEFAULTED -> false;
        };
    }
}
