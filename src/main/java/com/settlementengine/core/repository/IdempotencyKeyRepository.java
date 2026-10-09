package com.settlementengine.core.repository;

import com.settlementengine.core.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
}
