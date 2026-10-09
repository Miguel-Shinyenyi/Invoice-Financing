package com.settlementengine.core.invoicing;

public class AdvanceTransitionException extends RuntimeException {

    public AdvanceTransitionException(AdvanceStatus from, AdvanceStatus to) {
        super("Cannot transition advance from %s to %s".formatted(from, to));
    }
}
