# Kafka Events

## Purpose

Describes every Kafka topic, its event schema, and which services produce or consume it, centered on the settlement state machine.

## Current state

Built: all four settlement-transition topics, plus both reconciliation topics.

- `settlement.requested`: published when `createPendingSettlement` commits a `PENDING` settlement. Consumed by the read-model updater (`SettlementEventConsumer`). Not yet consumed by a fraud detection service (Phase 5.5).
- `settlement.confirmed` / `settlement.failed` / `settlement.unknown`: published when `finalizeSettlement` commits the corresponding terminal state. All consumed by the read-model updater. `settlement.unknown` is not separately consumed by the reconciliation engine — reconciliation doesn't listen for it, it *finds* `UNKNOWN` settlements itself each run via `SettlementRepository.findByExternalRefIsNotNull()`.
- `reconciliation.mismatch_found`: published by `ReconciliationService` when a new mismatch is flagged (not on a repeat detection of an already-`OPEN` one). No consumer yet — planned for an alerting service and the admin dashboard, neither of which exist yet.
- `reconciliation.resolved`: published both when reconciliation auto-resolves an `UNKNOWN` settlement and when `POST /reconciliation/mismatches/{id}/resolve` manually resolves a mismatch. No consumer yet, same reason.

Publishing goes through a **transactional outbox**, not a direct `KafkaTemplate.send()` inside the settlement transaction:

- `OutboxWriter.write(...)` inserts a row into `outbox_events` in the *same* transaction as the settlement/idempotency-key write (see `reconciliation.md` and `database.md`). This is what makes the publish side of this exactly the same problem the whole project is about: a DB commit and a Kafka publish are two independent systems, and doing them as two separate non-transactional steps would let one succeed while the other fails silently.
- `OutboxPublisher` polls unpublished rows (`@Scheduled`, 500ms default) and sends them via `KafkaTemplate<String, String>`, keyed by `aggregateId` (the settlement id) so all of a given settlement's events land in the same partition of *that topic* — see the ordering caveat below. A row is only marked `published_at` after a confirmed send; a failed send just leaves it for the next poll. The producer has `acks=all` and `enable.idempotence=true`, so retried sends from the client itself can't create duplicates on the broker side.
- Consumers must be idempotent, because outbox + Kafka together only guarantee at-least-once delivery, not exactly-once. `SettlementEventConsumer` handles this by upserting the read model rather than assuming an event is seen exactly once.

**Ordering caveat, found empirically**: partition-key ordering only holds *within one topic*. `settlement.requested` and `settlement.confirmed` are different topics with independent partitions and independent consumer threads, so `CONFIRMED` can be — and during testing, reliably was — consumed before `REQUESTED` for the same settlement. `SettlementEventConsumer` handles this two ways: every handler upserts (creates the row if it doesn't exist yet, rather than only the `requested` handler doing so), and a "last write wins by `occurredAt`" check discards a late-arriving event that's older than what's already stored, so an out-of-order `requested` can't downgrade a settlement that's already `CONFIRMED`.

**Second race, also found empirically**: two different topics' consumer threads can both observe "no row yet" for the same settlement and race to insert it, since Kafka gives no cross-topic coordination. `SettlementEventConsumer.upsert` catches the resulting unique-constraint violation and falls back to reading the row the other thread won, then applies the update to that — the same insert-race-and-fallback pattern already used in `SettlementService` (Phase 1), applied here to a different table.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Publishing goes through a transactional outbox (`outbox_events` table + polling `OutboxPublisher`), not a direct Kafka send inside the settlement transaction | Standard, industry-recommended answer to the dual-write problem: a DB commit and a Kafka publish can't be made atomic directly, so the event is written to the same transaction as the state change and handed to Kafka by a separate process, guaranteeing at-least-once delivery even if Kafka is briefly unavailable at commit time |
| 2026-07-11 | Outbox rows are only marked published after a blocking, acknowledged send (`.get()` on the producer future) | An unpublished row is always safe to retry; marking published before the ack risks silently dropping the event if the send actually failed |
| 2026-07-11 | Read-model consumer upserts in every handler and applies a last-write-wins check by `occurredAt`, instead of assuming `requested` always arrives first | Found empirically: Kafka's ordering guarantee is per-topic-partition, not cross-topic, so terminal-state events routinely arrive before the requested event on a different topic |
| 2026-07-11 | Read-model upsert also catches and recovers from a duplicate-key race on the first insert | Found empirically under the integration test: two different topics' consumer threads can race to create the same settlement's row; same fallback pattern as `SettlementService`'s idempotency-key race handling |
| 2026-07-11 | Separate topic per settlement state transition, instead of one generic `settlement.updated` topic | Consumers care about specific transitions; a dedicated `settlement.unknown` topic keeps that stream filterable per-consumer without a generic-event type tag |
| 2026-07-11 | JSON event payloads, not Avro, for v1 | Faster to iterate on schema early, can migrate to Avro with a schema registry once the shape stabilizes |
| 2026-07-11 | Reconciliation discovers `UNKNOWN` settlements by direct DB query each run, not by consuming `settlement.unknown` | Reconciliation needs to re-check *every* settlement with an external reference periodically (to catch drift on already-`CONFIRMED`/`FAILED` ones too, not just `UNKNOWN` ones), so a DB scan already has to exist; adding a Kafka consumer just for the `UNKNOWN` subset would be a second, redundant discovery path |
| 2026-07-11 | `reconciliation.mismatch_found` published once per new mismatch, not once per run that still finds it open | Matches the dedup rule in `reconciliation.md` — a consumer (once one exists) shouldn't get re-notified about the same unresolved mismatch every 60 seconds |

## Open questions

- Dead-letter queue strategy for failed consumers. The read-model consumer currently relies on Spring Kafka's default retry behavior for a failing record; no DLQ topic is configured yet. Revisit once a consumer exists where a silently-stuck message would actually matter (the read model is a non-critical dashboard view; this would matter more for e.g. an alerting consumer on `reconciliation.mismatch_found`).
- No consumer yet for either reconciliation topic — planned for an alerting service and admin dashboard, neither of which exist yet.
