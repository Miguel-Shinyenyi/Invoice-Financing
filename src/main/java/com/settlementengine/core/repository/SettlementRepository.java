package com.settlementengine.core.repository;

import com.settlementengine.core.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
}
