CREATE TABLE ledger_mismatches (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES ledger_accounts (id),
    stored_balance NUMERIC(19,4) NOT NULL,
    computed_balance NUMERIC(19,4) NOT NULL,
    details TEXT,
    resolution_status VARCHAR(20) NOT NULL CHECK (resolution_status IN ('OPEN', 'RESOLVED')),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_mismatches_open ON ledger_mismatches (account_id) WHERE resolution_status = 'OPEN';
