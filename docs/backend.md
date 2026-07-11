# Backend

## Purpose

Describes the Spring Boot service: its structure, API endpoints, and how it enforces idempotency and the settlement state machine described in `reconciliation.md`.

## Current state

Phase 1 core engine endpoints are built (`com.settlementengine.core.api`). All of them (except `/auth/**`, see below) now require a valid JWT `Authorization: Bearer` header as of Phase 2 — see `security.md`.

- `POST /settlements` - creates a settlement. Requires `ADMIN` or `SUPPORT` role (`@PreAuthorize`), plus an `Idempotency-Key` header (UUID). Body: `sourceAccountId`, `destinationAccountId`, `amount`, `currency`. Returns `201` with the settlement (including terminal status) on first processing, or the identical cached result on a retried duplicate. Returns `400` for a missing/malformed header or invalid body, `401` if unauthenticated, `403` if authenticated but not `ADMIN`/`SUPPORT`, `404` if either account doesn't exist, `422` for currency mismatch, self-settlement, or insufficient balance, `409` if the same key is currently mid-flight or was reused with a different payload.
- `GET /settlements/{id}` - returns settlement status and state. `404` if not found, `401` if unauthenticated, `403` if a `READ_ONLY` user doesn't own either side of the settlement (see `security.md`'s row-level permissions).
- `GET /accounts/{id}` - returns ledger account details and balance. `404` if not found, `401` if unauthenticated, `403` if a `READ_ONLY` user doesn't own the account.

### Auth endpoints (built, Phase 2)

- `POST /auth/login` - body: `username`, `password`. Returns `200` with `{accessToken, refreshToken, tokenType}`, `401` for any invalid-credentials case (unknown username or wrong password get the identical response and message, deliberately, to avoid leaking which one failed).
- `POST /auth/refresh` - body: `refreshToken`. Rotates the refresh token (old one revoked, new one issued) and returns a new access token alongside it. `401` if the token is unknown, expired, or already used.
- `POST /auth/logout` - body: `refreshToken`. Revokes it. `204` regardless of whether the token was already invalid (idempotent).

Internally, the request flow is split across two collaborating beans, not one:

- `SettlementService` - orchestrator, no `@Transactional` of its own. Hashes the request, checks for an existing idempotency key, calls the external gateway, delegates persistence to `SettlementTransactions`.
- `SettlementTransactions` - owns the two transactional boundaries: `createPendingSettlement` (validates accounts/currency/balance, writes the idempotency key + `PENDING` settlement atomically) and `finalizeSettlement` (writes the terminal status, ledger entries when `CONFIRMED`, and completes the idempotency key, atomically).

This split exists because `@Transactional` is proxy-based in Spring: a method calling another `@Transactional` method on `this` bypasses the proxy entirely and the annotation silently does nothing. Splitting the transactional methods into a separate bean, called from the orchestrator, ensures the transaction boundaries described in `reconciliation.md` are actually enforced, not just annotated.

Concurrent submissions of the same `Idempotency-Key` are handled by attempting the insert and catching the failure: `SettlementService` catches `org.springframework.dao.DataAccessException` around `createPendingSettlement` and re-reads the idempotency key on failure. This has to catch the broad `DataAccessException`, not just `DataIntegrityViolationException` — under real concurrent load, Postgres was observed (via the Testcontainers integration test) to report the same underlying race as a deadlock (`CannotAcquireLockException`) roughly as often as a clean unique-constraint violation, depending on timing of the competing index insertions.

A second, separate race was found the same way: the *winning* thread's `finalizeSettlement` call can itself deadlock, even though it's the only thread that ever reaches it for a given settlement. Concurrent losing transactions inserting into `settlements` take a `FOR KEY SHARE` lock on the referenced `idempotency_keys` row for FK validation; if the winner's `finalizeSettlement` then tries to `UPDATE` that same row (to mark it `COMPLETED`) while those losing transactions are still open, Postgres can deadlock the update against their still-held share locks. This is transient, not a correctness bug, so `SettlementService.finalizeWithRetry` retries only the `finalizeSettlement` call (not the whole flow) up to 5 times on `org.springframework.dao.TransientDataAccessException`, with a short backoff. Retrying only `finalizeSettlement` — not `createPendingSettlement` again — matters: the settlement is already durably `PENDING` by this point, so re-running the whole flow would make this thread see its own in-flight request as a conflict.

API documentation: `springdoc-openapi-starter-webmvc-ui` is wired in, exposing `/v3/api-docs` and Swagger UI at `/swagger-ui/index.html` (also reachable via `/swagger-ui.html`, which redirects). Controllers and `CreateSettlementRequest` carry `@Operation`/`@ApiResponse`/`@Schema` annotations so the generated docs describe the actual status-code and validation behavior, not just the method signatures — verified manually by starting the app and checking `/v3/api-docs` lists all three endpoints with the expected request/response shapes.

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
| 2026-07-11 | `finalizeSettlement` gets a bounded retry (5 attempts, short backoff) on `TransientDataAccessException`, separate from the insert-race fallback | Found empirically: the winning thread's terminal-state update can deadlock against losing transactions still holding an FK-check share lock on the same `idempotency_keys` row. This is transient and safe to retry on its own, unlike the insert race, which is resolved by reading the winner instead of retrying |
| 2026-07-11 | Exceptions mapped centrally in `GlobalExceptionHandler`: not-found → 404, currency/self-settlement/balance validation → 422, idempotency conflicts → 409, request validation → 400, `BadCredentialsException`/`InvalidTokenException` → 401 | One place decides HTTP semantics for domain errors instead of scattering status codes across controllers. `AccessDeniedException` (role/row-level denials) deliberately is *not* handled here — it's left to Spring Security's own `ExceptionTranslationFilter` and the custom `AccessDeniedHandler` in `SecurityConfig`, so both AOP-level (`@PreAuthorize`) and in-method (`RowLevelAccessGuard`) denials produce the same 403 shape |
| 2026-07-11 | REST over GraphQL for v1 | Simpler to secure and test for a first pass, GraphQL can be added later if the frontend needs it |

## Open questions

- None yet.
