# Architecture

## Purpose

Describes how the system's components connect and how data flows between them, with the reconciliation and idempotency engine as the center. Read `reconciliation.md` alongside this file, it explains the logic behind the flow described here.

## Current state

Steps 1-6 and 8 below are built and integration-tested end to end (including against a real Kafka broker via Testcontainers, and manually against both a local running instance and the deployed Kubernetes environment). Steps 7 (partially), 9, 10 are not yet built.

1. Client sends a settlement request to the Spring Boot API (`POST /settlements`) with an `Idempotency-Key` header. Built.
2. API checks the idempotency key via `SettlementTransactions.findExisting`. If already processed, returns the stored result immediately; if mid-flight, returns 409. Built.
3. If new, `SettlementTransactions.createPendingSettlement` writes a `PENDING` settlement and the idempotency key in one transaction, plus an `outbox_events` row for `settlement.requested` in that same transaction. Built. See `kafka-events.md` for why publishing goes through an outbox rather than a direct Kafka send here.
4. `SettlementService` calls the external system through the `ExternalSettlementGateway` interface to execute the money movement. Built, but the only implementation so far is `MockExternalSettlementGateway`, which always confirms — there's no real provider connector yet. A real connector is a later phase (invoice financing or a specific provider integration); the interface is the intended seam.
5. Based on the gateway response, `SettlementTransactions.finalizeSettlement` moves the settlement to `CONFIRMED`, `FAILED`, or `UNKNOWN` and, only on `CONFIRMED`, writes the double-entry ledger entries and debits/credits the accounts, plus the corresponding `outbox_events` row (`settlement.confirmed`/`failed`/`unknown`), all in the same transaction. Built.
6. A scheduled reconciliation job independently checks external records against internal settlement state, catching anything that never got a clean response, publishing `reconciliation.mismatch_found` when something doesn't line up. Built (Phase 4).
7. Fraud detection: a synchronous HTTP call from `InvoiceService.financeInvoice` to the ml-service, not an async Kafka consumer as originally sketched here — an async consumer can't reliably block a synchronous disbursement decision. Built (Phase 5.5); see `fraud-detection.md` for the full design and why it changed from this doc's original plan.
8. `OutboxPublisher` (scheduled poll, 500ms) sends unpublished outbox rows to Kafka; `SettlementEventConsumer` consumes all four topics and upserts `settlement_read_model` (CQRS). Built.
9. Receipts and audit logs get written to MinIO (self-hosted, not AWS S3 — see `storage.md`). Infrastructure is deployed (Phase 6) but the application-level feature itself is not yet built.
10. Redis caches account balances to avoid repeated Postgres reads on hot accounts. Not yet built.

Internally, the settlement flow is split across two Spring beans instead of one: `SettlementService` (orchestrator, no transactions of its own) and `SettlementTransactions` (owns the two transactional boundaries described in `reconciliation.md`). See `backend.md` for why — it's not a style choice, `@Transactional` would silently not apply otherwise.

The invoice financing layer sits on top of this: financing an invoice and collecting repayment are both settlements that go through the same idempotency and state machine logic, they don't have separate money-movement code paths.

All services run in Kubernetes, deployed via Docker images — a single-node k3s cluster on a self-hosted bare-metal server (not AWS EKS, see `server-setup.md` and `kubernetes.md`). Prometheus, Grafana, and OpenTelemetry tracing cover every service, since the state of any given settlement needs to be traceable end to end when something goes wrong. See `kubernetes.md` and `observability.md`.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Core problem reframed as idempotent settlement and reconciliation, invoice financing built on top rather than as the primary abstraction | Every fintech product that moves money shares this problem, building it as the core makes the project reusable and demonstrates the skill that actually gets tested in interviews |
| 2026-07-11 | Invoice financing uses the same settlement path as every other money movement, no special-cased logic | Prevents a second, untested path for money movement that skips idempotency or state tracking |
| 2026-07-11 | Use CQRS for the read model, built via a transactional outbox + Kafka rather than a direct write | Dashboard queries and settlement writes have different performance needs, separating them avoids lock contention; the outbox additionally solves the dual-write problem between the settlement DB transaction and the event publish, see `kafka-events.md` |
| 2026-07-11 | Fraud service is a separate Python process, not embedded in Spring Boot | Keeps ML dependencies isolated, allows independent scaling and redeployment |
