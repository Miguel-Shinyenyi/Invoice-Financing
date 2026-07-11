# Backend

## Purpose

Describes the Spring Boot service: its structure, API endpoints, and how it enforces idempotency and the settlement state machine described in `reconciliation.md`.

## Current state

Phase 1 core engine endpoints are built (`com.settlementengine.core.api`):

- `POST /settlements` - creates a settlement. Requires an `Idempotency-Key` header (UUID). Body: `sourceAccountId`, `destinationAccountId`, `amount`, `currency`. Returns `201` with the settlement (including terminal status) on first processing, or the identical cached result on a retried duplicate. Returns `400` for a missing/malformed header or invalid body, `404` if either account doesn't exist, `422` for currency mismatch, self-settlement, or insufficient balance, `409` if the same key is currently mid-flight or was reused with a different payload.
- `GET /settlements/{id}` - returns settlement status and state, `404` if not found.
- `GET /accounts/{id}` - returns ledger account details and balance, `404` if not found.

Internally, the request flow is split across two collaborating beans, not one:

- `SettlementService` - orchestrator, no `@Transactional` of its own. Hashes the request, checks for an existing idempotency key, calls the external gateway, delegates persistence to `SettlementTransactions`.
- `SettlementTransactions` - owns the two transactional boundaries: `createPendingSettlement` (validates accounts/currency/balance, writes the idempotency key + `PENDING` settlement atomically) and `finalizeSettlement` (writes the terminal status, ledger entries when `CONFIRMED`, and completes the idempotency key, atomically).

This split exists because `@Transactional` is proxy-based in Spring: a method calling another `@Transactional` method on `this` bypasses the proxy entirely and the annotation silently does nothing. Splitting the transactional methods into a separate bean, called from the orchestrator, ensures the transaction boundaries described in `reconciliation.md` are actually enforced, not just annotated.

Concurrent submissions of the same `Idempotency-Key` are handled by attempting the insert and catching the failure: `SettlementService` catches `org.springframework.dao.DataAccessException` around `createPendingSettlement` and re-reads the idempotency key on failure. This has to catch the broad `DataAccessException`, not just `DataIntegrityViolationException` — under real concurrent load, Postgres was observed (via the Testcontainers integration test) to report the same underlying race as a deadlock (`CannotAcquireLockException`) roughly as often as a clean unique-constraint violation, depending on timing of the competing index insertions.

Planned for Phase 4, reconciliation:

- `POST /reconciliation/runs` - trigger a reconciliation run manually, mainly for testing and admin use, the scheduled job is the primary trigger
- `GET /reconciliation/mismatches` - list unresolved mismatches
- `POST /reconciliation/mismatches/{id}/resolve` - manually resolve a mismatch with a recorded reason

Planned for Phase 5, invoice financing:

- `POST /invoices` - submit an invoice for financing
- `POST /invoices/{id}/finance` - approve and disburse an advance against an invoice, requires an `Idempotency-Key` header, goes through the settlement engine
- `GET /invoices/{id}` - get invoice and advance status

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Idempotency key required as a header on every money-movement endpoint, enforced at the API layer before it reaches business logic | Makes it impossible to accidentally add a new settlement path that skips idempotency, the check happens once centrally, not per-endpoint |
| 2026-07-11 | Orchestration (`SettlementService`) and transactional persistence (`SettlementTransactions`) split into two beans | Spring's `@Transactional` proxy is bypassed on self-invocation; without the split, the transaction boundaries required by `reconciliation.md` would be silently unenforced |
| 2026-07-11 | Race-fallback catches `DataAccessException`, not just `DataIntegrityViolationException` | Concurrent inserts for the same idempotency key were observed under Testcontainers to sometimes surface as a Postgres deadlock (`CannotAcquireLockException`) instead of a clean unique-constraint violation; both mean the same thing (someone else claimed this key first) and both must be handled |
| 2026-07-11 | Exceptions mapped centrally in `GlobalExceptionHandler`: not-found → 404, currency/self-settlement/balance validation → 422, idempotency conflicts → 409, request validation → 400 | One place decides HTTP semantics for domain errors instead of scattering status codes across controllers |
| 2026-07-11 | REST over GraphQL for v1 | Simpler to secure and test for a first pass, GraphQL can be added later if the frontend needs it |

## Open questions

- None yet.
