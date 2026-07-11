# Testing

## Purpose

Describes the test strategy, with particular emphasis on correctness under unreliable network conditions, since that's the core problem this project solves.

## Current state

Phase 1 test suite is built, 51 tests, all passing:

- Unit tests, no Spring context, plain JUnit 5 + Mockito: `SettlementStatusTest` (all 20 valid/invalid transition pairs), `LedgerAccountTest` (credit/debit/insufficient balance), `SettlementTest` (entity-level transition enforcement), `IdempotencyKeyTest`, `SettlementTransactionsTest` (validation rejections, `CONFIRMED`/`FAILED`/`UNKNOWN` finalize paths, balanced ledger entries), `SettlementServiceTest` (routing to cached/conflict/reused/fresh, race-fallback logic with a mocked `DataAccessException`).
- Integration tests, real Postgres via Testcontainers (`AbstractIntegrationTest` base, `@ServiceConnection`): `SettlementApiIntegrationTest` (full HTTP stack: create, retrieve, duplicate submission, missing header, insufficient balance, account not found) and `SettlementConcurrencyIntegrationTest`, which fires 8 concurrent threads at the same idempotency key against a real database and asserts exactly one settlement, exactly one pair of ledger entries, and correct final account balances.
- The concurrency test is not theoretical: running it against real Postgres reliably reproduces the race at the database level (`23505` unique-violation and, under heavier contention, Postgres deadlocks between competing index insertions) and proves the fallback path resolves both cases to a single settlement.
- Auth failure case tests are not applicable yet — no authentication exists until Phase 2.
- Not yet built: reconciliation tests (Phase 4), chaos/load tests (Phase 9).

Planned rules (unchanged):

- TDD for all business logic: write the failing test first
- State machine tests: every valid and invalid transition gets a test, including that `UNKNOWN` cannot silently become `CONFIRMED` without going through reconciliation
- Reconciliation tests: simulate external system responses that are delayed, wrong, or missing entirely, and verify mismatches get flagged rather than auto-resolved
- Chaos-style tests in Phase 9: simulate network timeouts and duplicate delivery at the external call boundary, verify no double-payment and no lost settlement occurs under load

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Idempotency and state machine transitions get dedicated test suites, not just incidental coverage from endpoint tests | This is the core value of the project, if it isn't tested explicitly and thoroughly, the project doesn't prove what it claims to |
| 2026-07-11 | TDD required, not optional | Matches how correctness-critical financial code should be written and demonstrates the practice for interviews |
| 2026-07-11 | Concurrency correctness proven against a real Postgres instance (Testcontainers), not mocked repositories | A unique-constraint race and its deadlock variant are database-level behaviors; a mocked repository cannot reproduce either, so the strongest idempotency claim in this project can only be proven this way |

## Open questions

- Load testing tool choice: k6 or Gatling. Decide in Phase 9.
