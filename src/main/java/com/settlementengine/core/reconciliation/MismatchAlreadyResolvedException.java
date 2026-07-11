package com.settlementengine.core.reconciliation;

import java.util.UUID;

public class MismatchAlreadyResolvedException extends RuntimeException {

    public MismatchAlreadyResolvedException(UUID mismatchId) {
        super("Mismatch " + mismatchId + " is already resolved");
    }
}
