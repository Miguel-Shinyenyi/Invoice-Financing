package com.settlementengine.core.domain;

import java.util.UUID;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(UUID idempotencyKey) {
        super("Idempotency key " + idempotencyKey + " was already used with a different request payload");
    }
}
