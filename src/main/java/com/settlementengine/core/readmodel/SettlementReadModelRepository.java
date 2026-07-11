package com.settlementengine.core.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SettlementReadModelRepository extends JpaRepository<SettlementReadModel, UUID> {
}
