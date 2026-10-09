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

### Observability, and the sidecars

Logs: a logback appender (demo profile only) keeps the last 2000 structured events in a ring buffer; the ml-service, started with `ML_LAB=1`, keeps its own ring and serves `GET /lab/logs`, which the backend polls every 2 s and merges into the same buffer with a `service` field (route absent without the flag, proven by `test_lab_support.py`). Metrics: `/lab/metrics` reads the `MeterRegistry` directly (outcomes, the two contention counters, `http_server_requests` percentiles, JVM threads, Hikari, outbox pending, read-model lag) and reproduces the four Grafana dashboards, each with the exact PromQL or SQL behind it; `/actuator/prometheus` is never proxied. Kafka: `/lab/kafka` reads partition offsets, per-group consumer lag (AdminClient, committed offsets against end offsets), consumers per topic (zero for `reconciliation.mismatch_found`) and the last 50 messages of a whitelisted topic through a reader with a throwaway group id that never commits. Traces, alerts, health: `LabUpstreamFetcher` is the only way the backend reaches Jaeger, Prometheus, Alertmanager or the ml-service; paths come from a fixed list, a path placeholder accepts only a safe segment, query values are encoded, and the base URLs are configuration (`LabUpstreamFetcherTest`, `LabJaegerServiceTest`). Traces are found by request, settlement or invoice id through the trace ids on the log lines; the OpenTelemetry agent puts `trace_id` in the logging MDC, which `LabLogAppender` copies. `LabRequestIndex` remembers recent requests (id, path, status, actor, time window) because `audit_log` and `outbox_events` do not store a request id; `/lab/correlate` joins logs, traces, audit rows (same actor inside the request's window, or target ids found in its log lines), outbox rows and the settlement inspector, and labels how each row was matched.

### Frontend and sandbox files

The Lab UI is described in `frontend.md`. `infra/docker-compose.lab.yml` starts everything with one command; `infra/lab/` is the Kubernetes version (own namespace `settlement-lab`, requests and limits on every container, not part of CI/CD); `infra/monitoring/alert-rules.yml` is the canonical alert-rule file (see `cicd.md`).

### Load runner

`LabLoadService` runs a validated `LoadPlan` (scenario, virtual users, duration, total requests, amount, optional fault profile) on a coordinator thread with a bounded VU pool. `LabLoadDriver` sends real HTTP to the app's own port on loopback with the sandbox ADMIN token, so the security chain, `RequestIdFilter`, audit logging and serialization are in the measured path. Once a second it samples throughput, p50/p95/p99 from a Micrometer histogram fed with every request's measured latency, status and outcome counts, `settlement.finalize.retries`, `settlement.unknown.fallback`, and Hikari active/idle/pending. At the end `LabLoadInvariants` checks the database: pool total unchanged, duplicate-key pair moved by exactly one settlement's amount (or zero if that settlement did not confirm), every touched balance equals its entries (via `LedgerConsistencyService`), no stale PENDING, outbox drained, read model caught up. UNKNOWN outcomes from injected faults are reported as a count with the Known gap 1 explanation, not as a failure. Fault profiles reach loopback requests through the `X-Lab-Run` header, honoured only from loopback and only for the active run (`LabRunFaultFilter`).

### What has been verified, and how

Backend: the Java suite (every lab test against real Postgres and Kafka containers, the fraud tests against the real ml-service image). Whole stack: `docker compose -f infra/docker-compose.lab.yml up` was run, and the Playwright suite in `frontend/e2e/` drove it at 375px and 1280px: every route renders with no horizontal overflow, one scenario runs on each, and the Part 4B screens were checked against the real OpenTelemetry agent, Jaeger, Prometheus and Alertmanager (a settlement trace shows SQL and Kafka spans; an invoice trace includes the outbound `POST /score` span into the fraud service; after a forced ML fault `MLServiceDown` reached firing and then recovered while financing still succeeded). Screenshots are in `docs/lab-screenshots/`. `e2e/isolation/` proves the login cookie and the persona cookie never reach the wrong backend. Server-sent events were confirmed to stream through the Next route handler. Not verified: streaming through the k3s Ingress (no cluster was available), and a from-scratch `docker compose ... --build` of the backend through the repo's own `Dockerfile` (its in-container Maven dependency download did not finish in reasonable time here; the verified stack used an identical runtime image built from a locally built jar).

A real load plan at the caps (20 users, 30 s, 5000 requests, 1.00 USD, no faults) ran 1948 requests in 40.7 s including invariant checks (47.8 requests/s average), p50 87 ms, p95 1143 ms, p99 2086 ms, max 2761 ms, Hikari peak 10 active and 13 pending. All invariants PASS. 91 settlements (4.7 percent) ended UNKNOWN with no reference, via 1924 finalize retries: deadlock contention on a 6-account pool exhausted the retry budget and the settlement fell back to UNKNOWN. That is the engine's documented fallback (`testing.md`), shown by the new counters, and it is Known gap 1 in action: nothing resolves those settlements.

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
| 2026-10-09 | The ledger-consistency invariant compares against a pre-run baseline: pre-existing drift is reported as such, only a change fails | Found by the first real run at the caps: a chaos test had hand-edited a pool balance (twice) and the run was blamed for it |
| 2026-10-09 | Duplicate-key invariant is relative to a baseline and uses a per-run key | A fixed key would make every later run a pure cache replay, and absolute balances break after any earlier run |
| 2026-10-09 | The alert rules live in `infra/monitoring/alert-rules.yml`; staging keeps its inline copy; `AlertRulesSyncTest` fails if they differ | Staging is deployed by `kubectl apply -f infra/k8s/`, which cannot read an outside file, and the CI workflow is not changed here |
| 2026-10-09 | SSE connections live at most `idle-timeout-minutes` (5) rather than closing on true idleness | `SseEmitter` timeouts are absolute; the front-end hook reconnects or falls back to polling |
| 2026-10-09 | Lab business accounts for invoice scenarios start with a 100.00 opening balance | A freshly financed business holds only the advance but repayment collects advance plus fee, so with zero it could never repay (observed) |

## Open questions

- Where the public sandbox runs is Miguel's decision (see `cicd.md`). Running it on the shared staging box would put load there, against the rule in `testing.md`.
- A reset truncates and reloads inside one transaction, but the external store swap happens just before commit. A reconciliation run that started on the old data in the instant between the two could open a spurious "no record" mismatch.
- The pool-total invariant can fail spuriously if a visitor settles from a pool account to a non-pool account during a run.
