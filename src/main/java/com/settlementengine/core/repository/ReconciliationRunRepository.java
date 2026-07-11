package com.settlementengine.core.repository;

import com.settlementengine.core.reconciliation.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
}
