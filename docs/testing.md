# Testing

## Purpose

Describes the test strategy, with particular emphasis on correctness under unreliable network conditions, since that's the core problem this project solves.

## Current state

Java test suite covers Phases 1-5.5, 178 tests, all passing; the Python `ml-service` has its own 9-test pytest suite (see below):

- Unit tests, no Spring context, plain JUnit 5 + Mockito: `SettlementStatusTest` (all 20 valid/invalid transition pairs), `LedgerAccountTest` (credit/debit/insufficient balance), `SettlementTest` (entity-level transition enforcement), `IdempotencyKeyTest`, `SettlementTransactionsTest` (validation rejections, `CONFIRMED`/`FAILED`/`UNKNOWN` finalize paths, balanced ledger entries), `SettlementServiceTest` (routing to cached/conflict/reused/fresh, race-fallback logic with a mocked `DataAccessException`).
- Integration tests, real Postgres via Testcontainers (`AbstractIntegrationTest` base, `@ServiceConnection`): `SettlementApiIntegrationTest` (full HTTP stack: create, retrieve, duplicate submission, missing header, insufficient balance, account not found) and `SettlementConcurrencyIntegrationTest`, which fires 8 concurrent threads at the same idempotency key against a real database and asserts exactly one settlement, exactly one pair of ledger entries, and correct final account balances.
- The concurrency test is not theoretical: running it against real Postgres reliably reproduces the race at the database level (`23505` unique-violation and, under heavier contention, Postgres deadlocks between competing index insertions) and proves the fallback path resolves both cases to a single settlement. A second, distinct deadlock (the winner's finalize step deadlocking against losing transactions' FK-check locks) surfaced the same way, under this same test, and is handled by a separate bounded retry — see `backend.md` and `reconciliation.md` decisions logs.
- Auth failure cases are covered (Phase 2 built this): `SettlementApiIntegrationTest` includes an unauthenticated-request and a wrong-role test; `AuthServiceTest` covers bad-credentials paths.
- Reconciliation test suite (Phase 4) built with the same rigor: unit tests for the domain entities (`ReconciliationRunTest`, `ReconciliationMismatchTest`) and the matching engine (`ReconciliationServiceTest`, 12 tests covering every branch — match, auto-resolve, mismatch, grace period, dedup), plus `ReconciliationIntegrationTest` proving the auto-resolve-updates-the-ledger path and the mismatch-flagged-not-auto-resolved path against real Postgres.
- Invoice financing test suite (Phase 5): `InvoiceTest`/`AdvanceTest`/`InvoiceStatusTest`/`AdvanceStatusTest` (state machines), `InvoiceServiceTest`/`InvoiceTransactionsTest` (financing math, the double-financing pessimistic-lock claim), `InvoiceRepaymentServiceTest`/`MockInvoicePaymentSourceTest` (exact-match/mismatch/overdue branches), plus `InvoiceFinancingIntegrationTest` proving the full submit → finance → detect payment → repay lifecycle against real Postgres.
- Fraud detection test suite (Phase 5.5): `FraudAssessmentTest` (entity), `HttpFraudDetectionClientTest` (parses a real success response, fails open on connection-refused/timeout/5xx — stubbed with the JDK's built-in `com.sun.net.httpserver.HttpServer`, no new test dependency needed), and `InvoiceServiceTest` extended with the fraud-wiring tests (`financeInvoiceBlocksWhenFraudCheckReturnsBlock...`, `financeInvoicePersistsAnAllowAssessment...`) proving the check runs before the invoice is claimed, an assessment is always persisted, and the feature values sent to the fraud service are computed correctly.
- `ml-service/test_main.py` (Python, pytest + FastAPI's `TestClient`): all four rules individually, combinations that cross the `BLOCK` threshold, score capping at 1.0, and the `/health` endpoint — 9 tests, TDD (written before `main.py`).
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
| 2026-07-12 | `HttpFraudDetectionClientTest` stubs the fraud service with the JDK's built-in `com.sun.net.httpserver.HttpServer`, not a mocking library | Avoids adding a new test dependency (WireMock/MockWebServer) for four small scenarios; found by manual end-to-end testing (not this test suite — see below) that this choice also happened to matter for a real bug |
| 2026-07-12 | Manual end-to-end verification against the real ml-service caught a bug the automated `HttpFraudDetectionClientTest` did not | The JDK `HttpClient`'s default HTTP/2-cleartext-upgrade attempt (`Upgrade: h2c`) was silently swallowing the POST body against uvicorn's HTTP/1.1 parser, but not against the JDK's own `HttpServer` test stub — both are "an HTTP/1.1 server", but only one is the actual target implementation. Lesson: a protocol-level client bug tied to a specific server's parsing behavior can pass every unit test and still fail for real; this is exactly the kind of thing the project's "manually verify end-to-end before considering a phase done" rule exists to catch. Fixed by forcing `HttpClient.Version.HTTP_1_1`; see `fraud-detection.md` |

## Open questions

- Load testing tool choice: k6 or Gatling. Decide in Phase 9.
