package com.settlementengine.core;

import com.settlementengine.core.domain.LedgerAccount;
import com.settlementengine.core.domain.LedgerEntry;
import com.settlementengine.core.repository.LedgerAccountRepository;
import com.settlementengine.core.repository.LedgerEntryRepository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Saves an account together with the OPENING entry that makes its stored balance match the net of
 * its ledger entries, the same way V7/load/seed-accounts.sql seed real accounts. Needed by any
 * integration test that reads an account through {@code GET /accounts/{id}}, which fails with a
 * {@code LedgerInconsistencyException} otherwise.
 */
public final class LedgerFixtures {

    private LedgerFixtures() {
    }

    public static LedgerAccount saveAccountWithOpeningEntry(LedgerAccountRepository ledgerAccountRepository,
                                                            LedgerEntryRepository ledgerEntryRepository,
                                                            UUID ownerId, BigDecimal balance, String currency) {
        LedgerAccount account = ledgerAccountRepository.save(
                new LedgerAccount(UUID.randomUUID(), ownerId, balance, currency));
        // ledger_entries.amount > 0: a zero-balance account needs no entry, it already nets to 0.
        if (balance.signum() > 0) {
            ledgerEntryRepository.save(LedgerEntry.opening(UUID.randomUUID(), account.getId(), balance));
        }
        return account;
    }
}
