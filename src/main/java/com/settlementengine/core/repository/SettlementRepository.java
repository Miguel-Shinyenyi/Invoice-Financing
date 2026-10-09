package com.settlementengine.core.repository;

import com.settlementengine.core.domain.Settlement;
import com.settlementengine.core.domain.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    List<Settlement> findByExternalRefIsNotNull();

    List<Settlement> findByStatusAndExternalRefIsNullAndUpdatedAtBefore(SettlementStatus status, Instant cutoff);
}
