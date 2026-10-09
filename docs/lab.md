# Lab

## Purpose

The Lab is a public sandbox in which a visitor runs the real settlement engine and watches it: idempotency, the state machine, the outbox and Kafka, the read model, reconciliation and the stale sweep, ledger consistency, invoice financing and fraud scoring, access control, structured logs, metrics, traces, alerts, and load and chaos runs. Visitors drive the real code against a separate stack, they do not watch a recording. Faults are injected only at the engine's boundaries, never inside its logic, and the six known gaps in the engine are shown as they are.

## Current state

Backend module `com.settlementengine.core.lab`. Every lab class carries `@LabComponent` / `@LabController` (`@Profile("demo")` plus `settlement-engine.demo.enabled=true`); without both, no lab bean and no `/lab/**` mapping exists. `LabGatingTest` proves it with an `ApplicationContextRunner` for the default profile and a `prod`-style profile. Endpoints are listed in `backend.md`, the guards in `security.md`. Frontend routes are in `frontend.md`.

### The sandbox stack

Separate from staging: its own Postgres (database `settlement_engine_lab`), Kafka, ml-service (`ML_LAB=1`), backend (`SPRING_PROFILES_ACTIVE=demo`), Jaeger, Prometheus and Alertmanager. `application-demo.yml` compresses time with the engine's own properties: reconciliation and sweep every 5 s, reconciliation grace 20 s, stale-pending grace 20 s, outbox poll 200 ms. `GET /lab/status` returns these so the UI never hard-codes them.

### What the Lab shows, by faults and gaps

| Fault boundary | Mechanism |
|---|---|
| Gateway call | `FaultInjectingGateway` (`@Primary` decorator over `MockExternalSystem`): REQUEST_LOST, RESPONSE_LOST, DECLINED, SLOW, or a probabilistic profile, read from a per-thread `LabFaultContext` |
| External record store | `POST /lab/external/{ref}/forget` and `/corrupt` |
| The row a human would edit | `POST /lab/accounts/{id}/hand-edit-balance`; `/repair-balance` is a lab-only stand-in for a human fixing it |
| Crash between the two transactions | `POST /lab/settlements/orphan` calls `createPendingSettlement` and stops |

Known gaps, each re-checked against `dev` at commit `b3d3b33` and unchanged: (1) UNKNOWN with no `externalRef` is never reconciled, (2) resolving a mismatch changes no data, (3) `REVERSED` has no code path, (4) no UI for ledger mismatches (closed by the frontend rewrite), (5) the mock stores are in memory, (6) nothing alerts on a mismatch. Gap 1 is on `/lab/reconciliation` (Stranded UNKNOWN), gap 2 in the resolve flow and the reopen loop, gap 3 in the state machine on `/lab`, gap 5 in the reset behaviour, gap 6 in the Unseen mismatches count, the zero-consumer topic on `/lab/events`, and the alert board's fixed row.

### Reset and seed

`lab/seed/lab-seed.sql` is deterministic and loaded in one transaction by `LabResetService` after truncating every data table. It also rebuilds the in-memory external record store from the loaded settlements (one record deliberately disagrees, the seeded open mismatch) and clears the payment source, the orphan list, the log ring, the request index and the load-run cache. It runs on startup, every `auto-reset-minutes` (30), and on `POST /lab/reset` (once a minute); it is skipped or refused while a load run is active. `LabSeedIntegrityTest` asserts every stored balance equals the net of its entries except one deliberately drifted account.

### Load runner

`LabLoadService` runs a validated `LoadPlan` (scenario, virtual users, duration, total requests, amount, optional fault profile) on a coordinator thread with a bounded VU pool. `LabLoadDriver` sends real HTTP to the app's own port on loopback with the sandbox ADMIN token, so the security chain, `RequestIdFilter`, audit logging and serialization are in the measured path. Once a second it samples throughput, p50/p95/p99 from a Micrometer histogram fed with every request's measured latency, status and outcome counts, `settlement.finalize.retries`, `settlement.unknown.fallback`, and Hikari active/idle/pending. At the end `LabLoadInvariants` checks the database: pool total unchanged, duplicate-key pair moved by exactly one settlement's amount (or zero if that settlement did not confirm), every touched balance equals its entries (via `LedgerConsistencyService`), no stale PENDING, outbox drained, read model caught up. UNKNOWN outcomes from injected faults are reported as a count with the Known gap 1 explanation, not as a failure. Fault profiles reach loopback requests through the `X-Lab-Run` header, honoured only from loopback and only for the active run (`LabRunFaultFilter`).

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-10-09 | Lab runs the real engine in a separate sandbox stack, never staging | `testing.md` already records why: staging is a shared bare-metal box |
| 2026-10-09 | `LabStartupGuardConfig` is a `BeanFactoryPostProcessor`, keyed on the demo profile alone | It must refuse a wrong database before Flyway or the first truncate can touch it, and must not depend on the enabled property being set |
| 2026-10-09 | `/lab/**` has its own earlier `SecurityFilterChain` (`permitAll`) instead of an edit to `SecurityConfig` | Without the lab gate the chain does not exist, so the real chain's `anyRequest().authenticated()` still covers any `/lab` path |
| 2026-10-09 | Playground answers a 200 envelope carrying the status the real endpoint would have | The steps are still worth showing for 409 and 422 outcomes. `LabHttpStatus` mirrors `GlobalExceptionHandler`'s mapping |
| 2026-10-09 | Small additive methods on `MockExternalSystem` (`clear`, `replaceAll`, `snapshot`, `recordCount`) and `MockInvoicePaymentSource` (`clear`, `paymentCount`) | A reset has to be able to clear the in-memory stores (Known gap 5); `replaceAll` swaps without ever exposing an empty store |
| 2026-10-09 | `GlobalExceptionHandler` gets `@Order(LOWEST_PRECEDENCE - 10)` | Guarantees it is consulted before the lab's last-resort handler, so domain exceptions keep their mapping |
| 2026-10-09 | `SettlementService` takes a `MeterRegistry` and counts `settlement.finalize.retries` and `settlement.unknown.fallback` | The load runner reports contention; the one production change outside the lab package (see `observability.md`) |
| 2026-10-09 | Duplicate-key invariant is relative to a baseline and uses a per-run key | A fixed key would make every later run a pure cache replay, and absolute balances break after any earlier run |
| 2026-10-09 | The alert rules live in `infra/monitoring/alert-rules.yml`; staging keeps its inline copy; `AlertRulesSyncTest` fails if they differ | Staging is deployed by `kubectl apply -f infra/k8s/`, which cannot read an outside file, and the CI workflow is not changed here |
| 2026-10-09 | SSE connections live at most `idle-timeout-minutes` (5) rather than closing on true idleness | `SseEmitter` timeouts are absolute; the front-end hook reconnects or falls back to polling |
| 2026-10-09 | Lab business accounts for invoice scenarios start with a 100.00 opening balance | A freshly financed business holds only the advance but repayment collects advance plus fee, so with zero it could never repay (observed) |

## Open questions

- Where the public sandbox runs is Miguel's decision (see `cicd.md`). Running it on the shared staging box would put load there, against the rule in `testing.md`.
- A reset truncates and reloads inside one transaction, but the external store swap happens just before commit. A reconciliation run that started on the old data in the instant between the two could open a spurious "no record" mismatch.
- The pool-total invariant can fail spuriously if a visitor settles from a pool account to a non-pool account during a run.
