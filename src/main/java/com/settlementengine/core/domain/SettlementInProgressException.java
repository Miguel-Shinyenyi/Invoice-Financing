package com.settlementengine.core.domain;

import java.util.UUID;

public class SettlementInProgressException extends RuntimeException {

    public SettlementInProgressException(UUID idempotencyKey) {
        super("A settlement for idempotency key " + idempotencyKey + " is already being processed");
    }
}
