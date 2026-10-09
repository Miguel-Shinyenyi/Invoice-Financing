package com.settlementengine.core.domain;

import java.util.UUID;

public class SelfSettlementException extends RuntimeException {

    public SelfSettlementException(UUID accountId) {
        super("Account " + accountId + " cannot settle to itself");
    }
}
