CREATE TABLE ledger_accounts (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    balance NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
    key UUID PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    response_snapshot TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE settlements (
    id UUID PRIMARY KEY,
    idempotency_key UUID NOT NULL UNIQUE REFERENCES idempotency_keys (key),
    source_account_id UUID NOT NULL REFERENCES ledger_accounts (id),
    destination_account_id UUID NOT NULL REFERENCES ledger_accounts (id),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'UNKNOWN', 'REVERSED')),
    external_ref VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_settlements_status ON settlements (status);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    settlement_id UUID NOT NULL REFERENCES settlements (id),
    account_id UUID NOT NULL REFERENCES ledger_accounts (id),
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entries_settlement ON ledger_entries (settlement_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id);
