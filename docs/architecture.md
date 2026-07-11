# Architecture

## Purpose

Describes how the system's components connect and how data flows between them, with the reconciliation and idempotency engine as the center. Read `reconciliation.md` alongside this file, it explains the logic behind the flow described here.

## Current state

Phase 1 core loop (steps 1-5 below, minus the Kafka publishes) is built and integration-tested end to end. Steps 6-10 are not yet built.

1. Client sends a settlement request to the Spring Boot API (`POST /settlements`) with an `Idempotency-Key` header. Built.
2. API checks the idempotency key via `SettlementTransactions.findExisting`. If already processed, returns the stored result immediately; if mid-flight, returns 409. Built.
3. If new, `SettlementTransactions.createPendingSettlement` writes a `PENDING` settlement and the idempotency key in one transaction. Built. The `settlement.requested` Kafka publish is not yet built (Phase 3).
4. `SettlementService` calls the external system through the `ExternalSettlementGateway` interface to execute the money movement. Built, but the only implementation so far is `MockExternalSettlementGateway`, which always confirms — there's no real provider connector yet. A real connector is a later phase (invoice financing or a specific provider integration); the interface is the intended seam.
5. Based on the gateway response, `SettlementTransactions.finalizeSettlement` moves the settlement to `CONFIRMED`, `FAILED`, or `UNKNOWN` and, only on `CONFIRMED`, writes the double-entry ledger entries and debits/credits the accounts. Built. The corresponding Kafka event publish is not yet built.
6. A scheduled reconciliation job independently checks external records against internal settlement state, catching anything that never got a clean response, publishing `reconciliation.mismatch_found` when something doesn't line up. Not yet built (Phase 4).
7. Fraud detection service consumes `settlement.requested` events and scores them before or alongside processing. Not yet built (Phase 5.5).
8. A Kafka consumer updates a read-optimized view for the dashboard (CQRS). Not yet built (Phase 3).
9. Receipts and audit logs get written to S3. Not yet built.
10. Redis caches account balances to avoid repeated Postgres reads on hot accounts. Not yet built.

Internally, the settlement flow is split across two Spring beans instead of one: `SettlementService` (orchestrator, no transactions of its own) and `SettlementTransactions` (owns the two transactional boundaries described in `reconciliation.md`). See `backend.md` for why — it's not a style choice, `@Transactional` would silently not apply otherwise.

The invoice financing layer sits on top of this: financing an invoice and collecting repayment are both settlements that go through the same idempotency and state machine logic, they don't have separate money-movement code paths.

All services run in Kubernetes, deployed via Docker images. Prometheus, Grafana, and OpenTelemetry tracing cover every service, since the state of any given settlement needs to be traceable end to end when something goes wrong. See `kubernetes.md` and `observability.md`.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Core problem reframed as idempotent settlement and reconciliation, invoice financing built on top rather than as the primary abstraction | Every fintech product that moves money shares this problem, building it as the core makes the project reusable and demonstrates the skill that actually gets tested in interviews |
| 2026-07-11 | Invoice financing uses the same settlement path as every other money movement, no special-cased logic | Prevents a second, untested path for money movement that skips idempotency or state tracking |
| 2026-07-11 | Use CQRS for the read model | Dashboard queries and settlement writes have different performance needs, separating them avoids lock contention |
| 2026-07-11 | Fraud service is a separate Python process, not embedded in Spring Boot | Keeps ML dependencies isolated, allows independent scaling and redeployment |
