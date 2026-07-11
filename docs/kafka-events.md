# Kafka Events

## Purpose

Describes every Kafka topic, its event schema, and which services produce or consume it, centered on the settlement state machine.

## Current state

Not yet built. Planned topics for Phase 3:

- `settlement.requested`: published when a settlement is accepted. Consumed by the read-model updater and the fraud detection service.
- `settlement.confirmed`: published when a settlement moves to `CONFIRMED`. Consumed by backend to finalize ledger entries and by the invoice financing layer to mark advances or repayments complete.
- `settlement.failed`: published when a settlement moves to `FAILED`. Consumed by backend to reverse any provisional ledger entries.
- `settlement.unknown`: published when a settlement can't be confirmed or denied after the external call. Consumed by the reconciliation engine as a priority item for the next run.
- `reconciliation.mismatch_found`: published when a reconciliation run finds a record that doesn't match. Consumed by an alerting service and the admin dashboard.
- `reconciliation.resolved`: published when a mismatch or an `UNKNOWN` settlement gets resolved. Consumed by backend to apply the final state.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Separate topic per settlement state transition, instead of one generic `settlement.updated` topic | Consumers care about specific transitions, `settlement.unknown` in particular needs its own consumer (reconciliation) that shouldn't have to filter a generic stream |
| 2026-07-11 | JSON event payloads, not Avro, for v1 | Faster to iterate on schema early, can migrate to Avro with a schema registry once the shape stabilizes |

## Open questions

- Dead-letter queue strategy for failed consumers. Decide once the consumer logic is written in Phase 3.
