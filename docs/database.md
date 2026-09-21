# Database

## Purpose

Describes the Postgres schema, migrations, and indexing decisions for the settlement engine and the invoice financing layer built on top of it.

## Current state

Phase 1 through Phase 5.5 tables are built and migrated via Flyway (`V1__init_core_schema.sql` through `V6__fraud_detection.sql`). Nothing remains unbuilt at the schema level.

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

### Event-driven layer tables (built, Phase 3)

- `outbox_events`: id, aggregate_type, aggregate_id, topic, payload (text, JSON), created_at, published_at (nullable). Partial index on `created_at` where `published_at is null`, for efficient polling of the unpublished backlog. Written in the same transaction as the settlement state change it represents (see `reconciliation.md`/`kafka-events.md` for why); a separate scheduled process (`OutboxPublisher`) reads it and marks rows published after a confirmed Kafka send.
- `settlement_read_model`: settlement_id (PK), source_account_id, destination_account_id, amount, currency, status, updated_at. A CQRS read side populated by `SettlementEventConsumer` off Kafka, not queried by any endpoint yet (no dashboard exists to read it — it exists to prove the event flow is correct, verified via tests and manual end-to-end checks).

### Reconciliation tables (built, Phase 4)

- `reconciliation_runs`: id, started_at, finished_at (nullable while running), records_checked, mismatches_found, status (`RUNNING`, `COMPLETED`, `FAILED`).
- `reconciliation_mismatches`: id, run_id (FK), settlement_id (FK), internal_state, external_state (nullable — no external record found), details (free text describing the discrepancy), resolution_status (`OPEN`, `RESOLVED`), resolved_at, created_at. Partial index on `settlement_id` where `resolution_status = 'OPEN'`, for the dedup check (don't re-flag a settlement that already has an open mismatch) and the "list open mismatches" endpoint.

### Invoice financing tables (built, Phase 5)

- `invoices`: id, business_account_id (FK to `ledger_accounts`, not a separate business entity — see decisions log), customer_reference (free text, the paying customer isn't modeled as an account of any kind), amount, currency, due_date, status (`ISSUED`, `FINANCED`, `REPAID`, `OVERDUE`), external_source_ref (unique — the key `InvoicePaymentSource` is checked by), created_at, updated_at. Indexed on `status` for the repayment scheduler's candidate query.
- `advances`: id, invoice_id (FK), amount_advanced, fee, disbursed_settlement_id (FK to `settlements`, `NOT NULL` — an advance only exists once disbursement has been attempted), repaid_settlement_id (FK, nullable until repaid), status (`DISBURSED`, `REPAID`, `DEFAULTED` — `DEFAULTED` isn't automated by anything yet), created_at. Indexed on `invoice_id`.

### Fraud detection tables (built, Phase 5.5)

- `fraud_assessments`: id, invoice_id (FK to `invoices`), score `NUMERIC(4,3)` (check between 0 and 1), decision (`ALLOW`, `BLOCK`), reasons (free text, comma-joined rule names, nullable when nothing was flagged), created_at. Indexed on `invoice_id`. One row is written per `financeInvoice` attempt regardless of decision — including attempts that end up `BLOCK`ed, and including "no signal" rows produced by the fail-open path when the ML service is unreachable (see `fraud-detection.md`) — so the table is a full audit trail of every fraud check ever run, not just the ones that passed.

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
| 2026-07-11 | `advances` references `disbursed_settlement_id` and `repaid_settlement_id` separately | An advance has two distinct money movements with independent success/failure/unknown states, collapsing them into one field would lose that |
| 2026-07-11 | No separate audit trail table for `reconciliation_mismatches`; resolves the open question below | `audit_log` already records who resolved a mismatch and when (via `ReconciliationController`'s `RESOLVE_RECONCILIATION_MISMATCH` action), and `reconciliation_mismatches.details` records why (appended on resolution). A third table would duplicate both without adding information |
| 2026-07-11 | `reconciliation_mismatches`'s partial index on `settlement_id` (open rows only) is a plain index, not a unique constraint | "At most one open mismatch per settlement" is enforced at the application layer (`ReconciliationService` checks before inserting), since it's a business rule that needs a friendly check-then-decide path, not a hard constraint that would throw on violation; the index just makes that check and the "list open mismatches" query fast |
| 2026-07-12 | `invoices.business_account_id` references `ledger_accounts` directly, not a separate `businesses` table | There's no business/customer entity modeled anywhere in this schema (see the `users.owner_id` decision above, same reasoning) — a `ledger_accounts` row already *is* the business's account from the settlement engine's point of view, so a separate table would just duplicate the id with no new information |
| 2026-07-12 | `advances.disbursed_settlement_id` is `NOT NULL` | An `Advance` row is only ever created *after* `SettlementService.createSettlement` returns for the disbursement (see `InvoiceService.financeInvoice`) — there's no intermediate "advance approved but not yet disbursed" state to represent |
| 2026-07-12 | `fraud_assessments.reasons` is a single comma-joined free-text column, not a child table or array column | Matches the existing `reconciliation_mismatches.details` pattern in this schema; reasons are a fixed small set of rule names for display/audit purposes only, never queried on individually |
| 2026-07-12 | `fraud_assessments` has no foreign key back to `advances` or `settlements` | An assessment can exist without ever producing an advance (the `BLOCK` case) — tying it to `invoices.id` only keeps the row meaningful in both outcomes |
| 2026-09-21 | `StalePendingSettlementSweepService`'s candidate query (`status = PENDING`, `external_ref IS NULL`, `updated_at` older than its grace period) reuses the existing `idx_settlements_status` index, no new migration | Closes the crash-recovery gap described in `reconciliation.md`'s "Recovering from a crash before finalization" section. `PENDING` settlements are a small, transient subset of the table by design (every settlement resolves to a terminal or `UNKNOWN` state within one gateway round trip in the normal case), so an index scan on `status` narrows the candidate set enough before the `external_ref IS NULL`/`updated_at` filters apply — the same reasoning already applied to `reconciliation_mismatches`'s partial open-mismatch index above, just via an existing single-column index rather than a new partial one, since this doesn't need write-time uniqueness enforcement the way that one's dedup check does |

## Open questions

- What happens if balance becomes insufficient between initiation-time validation and the `CONFIRMED` ledger write (a race between two settlements on the same source account). This is exactly the class of drift the reconciliation engine (Phase 4) is positioned to catch via the amount-mismatch path, but no test exercises this specific race yet — revisit if it's ever observed in practice.
- Nothing in this schema credits `ledger_accounts.balance` when a business's *external* bank account receives the customer's invoice payment — repayment collection (`invoices.business_account_id` → platform) assumes the business's ledger balance already covers `amount_advanced + fee`. Modeling that properly would need a distinct "external deposit" concept this project doesn't have. Documented as a known simplification in `reconciliation.md`.
