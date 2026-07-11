# Testing

## Purpose

Describes the test strategy, with particular emphasis on correctness under unreliable network conditions, since that's the core problem this project solves.

## Current state

Test suite covers Phases 1-4, 116 tests, all passing:

- Unit tests, no Spring context, plain JUnit 5 + Mockito: `SettlementStatusTest` (all 20 valid/invalid transition pairs), `LedgerAccountTest` (credit/debit/insufficient balance), `SettlementTest` (entity-level transition enforcement), `IdempotencyKeyTest`, `SettlementTransactionsTest` (validation rejections, `CONFIRMED`/`FAILED`/`UNKNOWN` finalize paths, balanced ledger entries), `SettlementServiceTest` (routing to cached/conflict/reused/fresh, race-fallback logic with a mocked `DataAccessException`).
- Integration tests, real Postgres via Testcontainers (`AbstractIntegrationTest` base, `@ServiceConnection`): `SettlementApiIntegrationTest` (full HTTP stack: create, retrieve, duplicate submission, missing header, insufficient balance, account not found) and `SettlementConcurrencyIntegrationTest`, which fires 8 concurrent threads at the same idempotency key against a real database and asserts exactly one settlement, exactly one pair of ledger entries, and correct final account balances.
- The concurrency test is not theoretical: running it against real Postgres reliably reproduces the race at the database level (`23505` unique-violation and, under heavier contention, Postgres deadlocks between competing index insertions) and proves the fallback path resolves both cases to a single settlement. A second, distinct deadlock (the winner's finalize step deadlocking against losing transactions' FK-check locks) surfaced the same way, under this same test, and is handled by a separate bounded retry — see `backend.md` and `reconciliation.md` decisions logs.
- Auth failure cases are covered (Phase 2 built this): `SettlementApiIntegrationTest` includes an unauthenticated-request and a wrong-role test; `AuthServiceTest` covers bad-credentials paths.
- Reconciliation test suite (Phase 4) built with the same rigor: unit tests for the domain entities (`ReconciliationRunTest`, `ReconciliationMismatchTest`) and the matching engine (`ReconciliationServiceTest`, 12 tests covering every branch — match, auto-resolve, mismatch, grace period, dedup), plus `ReconciliationIntegrationTest` proving the auto-resolve-updates-the-ledger path and the mismatch-flagged-not-auto-resolved path against real Postgres.
- Not yet built: chaos/load tests (Phase 9).

Planned rules (unchanged):

- TDD for all business logic: write the failing test first
- State machine tests: every valid and invalid transition gets a test, including that `UNKNOWN` cannot silently become `CONFIRMED` without going through reconciliation
- Chaos-style tests in Phase 9: simulate network timeouts and duplicate delivery at the external call boundary, verify no double-payment and no lost settlement occurs under load

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Idempotency and state machine transitions get dedicated test suites, not just incidental coverage from endpoint tests | This is the core value of the project, if it isn't tested explicitly and thoroughly, the project doesn't prove what it claims to |
| 2026-07-11 | TDD required, not optional | Matches how correctness-critical financial code should be written and demonstrates the practice for interviews |
| 2026-07-11 | Concurrency correctness proven against a real Postgres instance (Testcontainers), not mocked repositories | A unique-constraint race and its deadlock variant are database-level behaviors; a mocked repository cannot reproduce either, so the strongest idempotency claim in this project can only be proven this way |
| 2026-07-11 | `AbstractIntegrationTest` carries `@DirtiesContext(classMode = AFTER_CLASS)` | Found empirically running the full suite: every integration test class shares one `static` `@Container` Postgres field (declared once, in the shared abstract base — that's how Java static fields work), and Testcontainers restarts that container per test class, reassigning its host port each time. Without forcing Spring to drop its cached `ApplicationContext` after each class, a later test class could get a cached context whose `DataSource` still pointed at an earlier, now-dead port ("connection refused") |
| 2026-07-11 | Reconciliation integration tests assert against the specific settlement/mismatch under test, not a run's global counts | Same shared-context reality: two test methods in the same class use the same database with no per-test rollback, so one test's settlement can be present (and contribute to counts) when another test's reconciliation run executes. Scoped assertions (e.g. "no open mismatch for *this* settlement id") are correct regardless of what else is in the database; global-count assertions aren't |

## Open questions

- Load testing tool choice: k6 or Gatling. Decide in Phase 9.
