package com.settlementengine.core.domain;

import java.util.UUID;

public class SettlementNotFoundException extends RuntimeException {

    public SettlementNotFoundException(UUID settlementId) {
        super("No settlement with id " + settlementId);
    }
}
