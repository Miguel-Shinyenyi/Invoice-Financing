package com.settlementengine.core.repository;

import com.settlementengine.core.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    // OPENING and CREDIT add, DEBIT subtracts. Should always equal the account's stored balance.
    @Query("SELECT COALESCE(SUM(CASE WHEN e.entryType = com.settlementengine.core.domain.EntryType.DEBIT " +
           "THEN -e.amount ELSE e.amount END), 0) FROM LedgerEntry e WHERE e.accountId = :accountId")
    BigDecimal sumNetByAccountId(@Param("accountId") UUID accountId);
}
