CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    business_account_id UUID NOT NULL REFERENCES ledger_accounts (id),
    customer_reference VARCHAR(255) NOT NULL,
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    due_date TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ISSUED', 'FINANCED', 'REPAID', 'OVERDUE')),
    external_source_ref VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invoices_status ON invoices (status);

CREATE TABLE advances (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoices (id),
    amount_advanced NUMERIC(19,4) NOT NULL CHECK (amount_advanced > 0),
    fee NUMERIC(19,4) NOT NULL CHECK (fee >= 0),
    disbursed_settlement_id UUID NOT NULL REFERENCES settlements (id),
    repaid_settlement_id UUID REFERENCES settlements (id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DISBURSED', 'REPAID', 'DEFAULTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_advances_invoice ON advances (invoice_id);
