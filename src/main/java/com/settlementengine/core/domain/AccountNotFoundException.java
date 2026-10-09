package com.settlementengine.core.domain;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super("No ledger account with id " + accountId);
    }
}
