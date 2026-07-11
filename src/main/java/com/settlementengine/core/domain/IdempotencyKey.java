package com.settlementengine.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    private UUID key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_snapshot", columnDefinition = "TEXT")
    private String responseSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyKeyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdempotencyKey() {
    }

    public IdempotencyKey(UUID key, String requestHash) {
        this.key = key;
        this.requestHash = requestHash;
        this.status = IdempotencyKeyStatus.IN_PROGRESS;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void complete(String responseSnapshot) {
        this.responseSnapshot = responseSnapshot;
        this.status = IdempotencyKeyStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public UUID getKey() {
        return key;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseSnapshot() {
        return responseSnapshot;
    }

    public IdempotencyKeyStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
