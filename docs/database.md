# Database

## Purpose

Describes the Postgres schema, migrations, and indexing decisions for the settlement engine and the invoice financing layer built on top of it.

## Current state

Phase 1 core engine tables are built and migrated via Flyway (`V1__init_core_schema.sql`). Reconciliation, audit, and invoice financing tables are not yet built.

### Core engine tables (built, Phase 1)

- `ledger_accounts`: id, owner_id, balance `NUMERIC(19,4)`, currency `VARCHAR(3)`, version (optimistic lock), created_at
- `idempotency_keys`: key (primary key, UUID), request_hash, response_snapshot (text, JSON), status (`IN_PROGRESS`, `COMPLETED`), created_at, updated_at
- `settlements`: id, idempotency_key (unique, FK to `idempotency_keys.key`), source_account_id, destination_account_id (both FK to `ledger_accounts`), amount `NUMERIC(19,4)` (check > 0), currency, status (`PENDING`, `CONFIRMED`, `FAILED`, `UNKNOWN`, `REVERSED`), external_ref, created_at, updated_at; indexed on `status`
- `ledger_entries`: id, settlement_id (FK), account_id (FK), entry_type (`DEBIT`, `CREDIT`), amount (check > 0), created_at; indexed on `settlement_id` and `account_id`

Ledger entries follow double-entry bookkeeping: a settlement only produces ledger entries once it reaches `CONFIRMED` (two entries, debit source + credit destination, net zero). `PENDING`, `FAILED`, and `UNKNOWN` settlements never touch the ledger. All entity IDs (`UUID`) are generated in application code, not by the database, so no `pgcrypto`/`uuid-ossp` extension is required.

### Not yet built

- `reconciliation_runs`, `reconciliation_mismatches` (Phase 4)
- `audit_log` (Phase 2, alongside security work)
- `invoices`, `advances` (Phase 5)

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Currency columns are `VARCHAR(3)`, not `CHAR(3)` | Hibernate's schema validation (`ddl-auto: validate`) expects `VARCHAR` for a mapped `String` column by default; `CHAR(3)` caused a validation failure on startup against a real Postgres instance, caught by the Testcontainers integration tests |
| 2026-07-11 | `idempotency_keys` as its own table with a unique constraint on `key` | Database-level uniqueness is the actual enforcement mechanism, application-level checks alone have a race window |
| 2026-07-11 | `settlements.status` includes `UNKNOWN` as a valid state, not just a transient one | Matches the state machine in `reconciliation.md`, the schema should not force a premature confident state |
| 2026-07-11 | Ledger entries only written on `CONFIRMED`, never for `PENDING`/`FAILED`/`UNKNOWN` | An unconfirmed movement shouldn't appear as an accounted-for fact in the ledger; keeps the ledger always representing only real, confirmed money movement |
| 2026-07-11 | Balance sufficiency validated once, at settlement-initiation time, before the external call | The external system's confirmation represents money that has already moved in the real world; the internal ledger must not be able to refuse to record a confirmed movement, so insufficiency must be caught before committing to the external call, not after |
| 2026-07-11 | Double-entry ledger design | Standard for financial systems, makes reconciliation and auditing straightforward |
| 2026-07-11 | Optimistic locking (`@Version`) on `ledger_accounts.balance` | Prevents lost updates under concurrent writes without holding long locks |
| 2026-07-11 | `advances` references `disbursed_settlement_id` and `repaid_settlement_id` separately (planned, Phase 5) | An advance has two distinct money movements with independent success/failure/unknown states, collapsing them into one field would lose that |

## Open questions

- Whether `reconciliation_mismatches` needs its own audit trail separate from `audit_log`, given how central mismatch resolution is to this project. Leaning yes, decide in Phase 4.
- What happens if balance becomes insufficient between initiation-time validation and the `CONFIRMED` ledger write (a race between two settlements on the same source account). Currently this would surface as a real `InsufficientBalanceException` at finalize time after the external system already confirmed the transfer — a genuine inconsistency that Phase 1 does not attempt to resolve automatically. Revisit when the reconciliation engine (Phase 4) exists, since this is exactly the class of drift it's meant to catch.
