CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    records_checked INT NOT NULL DEFAULT 0,
    mismatches_found INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE reconciliation_mismatches (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES reconciliation_runs (id),
    settlement_id UUID NOT NULL REFERENCES settlements (id),
    internal_state VARCHAR(20) NOT NULL,
    external_state VARCHAR(20),
    details TEXT,
    resolution_status VARCHAR(20) NOT NULL CHECK (resolution_status IN ('OPEN', 'RESOLVED')),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reconciliation_mismatches_open ON reconciliation_mismatches (settlement_id) WHERE resolution_status = 'OPEN';
