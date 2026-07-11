# Database

## Purpose

Describes the Postgres schema, migrations, and indexing decisions for the settlement engine and the invoice financing layer built on top of it.

## Current state

Phase 1 core engine tables and Phase 2 security tables are built and migrated via Flyway (`V1__init_core_schema.sql`, `V2__security_and_access_control.sql`). Reconciliation and invoice financing tables are not yet built.

### Core engine tables (built, Phase 1)

- `ledger_accounts`: id, owner_id, balance `NUMERIC(19,4)`, currency `VARCHAR(3)`, version (optimistic lock), created_at
- `idempotency_keys`: key (primary key, UUID), request_hash, response_snapshot (text, JSON), status (`IN_PROGRESS`, `COMPLETED`), created_at, updated_at
- `settlements`: id, idempotency_key (unique, FK to `idempotency_keys.key`), source_account_id, destination_account_id (both FK to `ledger_accounts`), amount `NUMERIC(19,4)` (check > 0), currency, status (`PENDING`, `CONFIRMED`, `FAILED`, `UNKNOWN`, `REVERSED`), external_ref, created_at, updated_at; indexed on `status`
- `ledger_entries`: id, settlement_id (FK), account_id (FK), entry_type (`DEBIT`, `CREDIT`), amount (check > 0), created_at; indexed on `settlement_id` and `account_id`

Ledger entries follow double-entry bookkeeping: a settlement only produces ledger entries once it reaches `CONFIRMED` (two entries, debit source + credit destination, net zero). `PENDING`, `FAILED`, and `UNKNOWN` settlements never touch the ledger. All entity IDs (`UUID`) are generated in application code, not by the database, so no `pgcrypto`/`uuid-ossp` extension is required.

### Security tables (built, Phase 2)

- `users`: id, username (unique), password_hash (BCrypt), role (`ADMIN`, `SUPPORT`, `READ_ONLY`), owner_id (nullable UUID), created_at. `owner_id` links a `READ_ONLY` user to the `ledger_accounts.owner_id` they're allowed to see; `NULL` for `ADMIN`/`SUPPORT`, who aren't row-restricted.
- `refresh_tokens`: id, user_id (FK), token_hash (SHA-256 hex, unique — the raw token is never stored), expires_at, revoked_at (nullable), created_at; indexed on `user_id`.
- `audit_log`: id, actor_id (nullable — unauthenticated attempts have no actor), action, target_table, target_id, outcome (`SUCCESS`, `DENIED`, `FAILURE`), created_at; indexed on `actor_id`.

### Not yet built

- `reconciliation_runs`, `reconciliation_mismatches` (Phase 4)
- `invoices`, `advances` (Phase 5)

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | `audit_log` gets an `outcome` column (`SUCCESS`/`DENIED`/`FAILURE`) not in the original sketch | `security.md` requires logging attempts "successful or not" — without an outcome field there's no way to tell a denied attempt from a successful one, defeating the stated purpose |
| 2026-07-11 | `refresh_tokens.token_hash` stores a SHA-256 hex digest, never the raw token | Same principle as password hashing: a stolen database dump must not hand out usable credentials. Matches the existing `request_hash` pattern already used for idempotency keys |
| 2026-07-11 | `users.owner_id` is nullable, not a required FK to a business/owner table | There's no separate business/owner entity in the schema yet — `owner_id` is just the UUID already used in `ledger_accounts.owner_id`. Nullable because staff roles (`ADMIN`, `SUPPORT`) aren't tied to a single owner |
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
