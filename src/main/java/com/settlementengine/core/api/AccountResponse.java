package com.settlementengine.core.api;

import com.settlementengine.core.domain.LedgerAccount;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID ownerId,
        BigDecimal balance,
        String currency,
        Instant createdAt) {

    public static AccountResponse from(LedgerAccount account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerId(),
                account.getBalance(),
                account.getCurrency(),
                account.getCreatedAt());
    }
}
