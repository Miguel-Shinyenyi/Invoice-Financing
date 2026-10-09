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
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    private UUID id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "records_checked", nullable = false)
    private int recordsChecked;

    @Column(name = "mismatches_found", nullable = false)
    private int mismatchesFound;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationRunStatus status;

    protected ReconciliationRun() {
    }

    public ReconciliationRun(UUID id, Instant startedAt) {
        this.id = id;
        this.startedAt = startedAt;
        this.recordsChecked = 0;
        this.mismatchesFound = 0;
        this.status = ReconciliationRunStatus.RUNNING;
    }

    public void complete(int recordsChecked, int mismatchesFound) {
        this.recordsChecked = recordsChecked;
        this.mismatchesFound = mismatchesFound;
        this.finishedAt = Instant.now();
        this.status = ReconciliationRunStatus.COMPLETED;
    }

    public void fail() {
        this.finishedAt = Instant.now();
        this.status = ReconciliationRunStatus.FAILED;
    }

    public UUID getId() {
        return id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getRecordsChecked() {
        return recordsChecked;
    }

    public int getMismatchesFound() {
        return mismatchesFound;
    }

    public ReconciliationRunStatus getStatus() {
        return status;
    }
}
