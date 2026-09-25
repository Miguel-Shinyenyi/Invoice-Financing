package com.settlementengine.core.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_mismatches")
public class LedgerMismatch {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "stored_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal storedBalance;

    @Column(name = "computed_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal computedBalance;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 20)
    private MismatchResolutionStatus resolutionStatus;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerMismatch() {
    }

    public LedgerMismatch(UUID id, UUID accountId, BigDecimal storedBalance, BigDecimal computedBalance,
                          String details) {
        this.id = id;
        this.accountId = accountId;
        this.storedBalance = storedBalance;
        this.computedBalance = computedBalance;
        this.details = details;
        this.resolutionStatus = MismatchResolutionStatus.OPEN;
        this.createdAt = Instant.now();
    }

    public void resolve(String resolutionNote) {
        if (resolutionStatus == MismatchResolutionStatus.RESOLVED) {
            throw new MismatchAlreadyResolvedException(id);
        }
        this.details = details + " | Resolution: " + resolutionNote;
        this.resolutionStatus = MismatchResolutionStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public BigDecimal getStoredBalance() {
        return storedBalance;
    }

    public BigDecimal getComputedBalance() {
        return computedBalance;
    }

    public String getDetails() {
        return details;
    }

    public MismatchResolutionStatus getResolutionStatus() {
        return resolutionStatus;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
