package com.settlementengine.core.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_mismatches")
public class ReconciliationMismatch {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "settlement_id", nullable = false)
    private UUID settlementId;

    @Column(name = "internal_state", nullable = false, length = 20)
    private String internalState;

    @Column(name = "external_state", length = 20)
    private String externalState;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 20)
    private MismatchResolutionStatus resolutionStatus;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReconciliationMismatch() {
    }

    public ReconciliationMismatch(UUID id, UUID runId, UUID settlementId, String internalState,
                                   String externalState, String details) {
        this.id = id;
        this.runId = runId;
        this.settlementId = settlementId;
        this.internalState = internalState;
        this.externalState = externalState;
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

    public UUID getRunId() {
        return runId;
    }

    public UUID getSettlementId() {
        return settlementId;
    }

    public String getInternalState() {
        return internalState;
    }

    public String getExternalState() {
        return externalState;
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
