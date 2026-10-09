package com.settlementengine.core.domain;

import java.math.BigDecimal;
import java.util.UUID;

public class LedgerInconsistencyException extends RuntimeException {

    public LedgerInconsistencyException(UUID accountId, BigDecimal storedBalance, BigDecimal computedBalance) {
        super("Ledger account " + accountId + " is inconsistent: stored balance " + storedBalance
                + " does not match net of ledger entries " + computedBalance);
    }
}
