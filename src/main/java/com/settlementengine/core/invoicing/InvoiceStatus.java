package com.settlementengine.core.invoicing;

public enum InvoiceStatus {
    ISSUED,
    FINANCED,
    REPAID,
    OVERDUE;

    public boolean canTransitionTo(InvoiceStatus target) {
        return switch (this) {
            case ISSUED -> target == FINANCED;
            case FINANCED -> target == REPAID || target == OVERDUE;
            case OVERDUE -> target == REPAID;
            case REPAID -> false;
        };
    }
}
