package com.settlementengine.core.domain;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(UUID accountId, BigDecimal balance, BigDecimal requestedDebit) {
        super("Account %s has balance %s, cannot debit %s".formatted(accountId, balance, requestedDebit));
    }
}
