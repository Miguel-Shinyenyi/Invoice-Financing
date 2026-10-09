CREATE TABLE fraud_assessments (
    id UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoices (id),
    score NUMERIC(4,3) NOT NULL CHECK (score >= 0 AND score <= 1),
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('ALLOW', 'BLOCK')),
    reasons TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fraud_assessments_invoice ON fraud_assessments (invoice_id);
