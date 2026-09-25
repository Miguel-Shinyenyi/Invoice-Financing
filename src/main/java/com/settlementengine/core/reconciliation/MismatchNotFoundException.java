package com.settlementengine.core.reconciliation;

import java.util.UUID;

public class MismatchNotFoundException extends RuntimeException {

    public MismatchNotFoundException(UUID mismatchId) {
        super("No mismatch with id " + mismatchId);
    }
}
