package com.settlementengine.core.domain;

public enum EntryType {
    DEBIT,
    CREDIT,
    // An account's starting balance, which has no settlement behind it. Adds, like CREDIT.
    OPENING
}
