package com.settlementengine.core.repository;

import com.settlementengine.core.domain.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
}
