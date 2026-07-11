# Reconciliation and Idempotency Engine

## Purpose

This is the core of the project. Every other component (invoice financing, provider connectors, the dashboard) sits on top of this. This doc describes how the system guarantees correct money movement despite unreliable networks and external systems it doesn't control.

## The problem, stated precisely

When this system tells an external party to move money, the external call can fail in three ways that all look the same from the caller's side:

1. The request never reached the external system. Nothing happened.
2. The request reached the external system, it processed successfully, but the response was lost. Something happened, but we don't know it.
3. The request reached the external system twice, because of a retry after a timeout. Something happened twice, unless the external system deduplicates on its own.

A network timeout looks identical in all three cases. The system cannot tell them apart just by looking at the failed call. Retrying blindly risks double-payment. Not retrying risks losing a transfer that actually succeeded. The engine exists to make this decision safely instead of guessing.

## Idempotency

Every settlement request carries a client-generated idempotency key, a UUID generated once when the request is first created, not regenerated on retry.

- Before processing a settlement request, the engine checks if that idempotency key has been seen before.
- If it has, and the prior attempt completed, the engine returns the prior result without reprocessing. The money does not move twice.
- If it has, and the prior attempt is still in progress, the engine returns a conflict response rather than starting a second execution.
- If it hasn't been seen, the engine processes it and stores the key with the result before returning.

The idempotency key and the result are stored in the same database transaction as the ledger write. This prevents a race where the ledger updates but the key never gets recorded, which would defeat the whole mechanism on the next retry.

## Settlement state machine

Every settlement has exactly one state at any time:

- `PENDING`: request accepted, external call not yet confirmed
- `CONFIRMED`: external system confirmed the money moved
- `FAILED`: external system confirmed the money did not move
- `UNKNOWN`: the external call timed out or errored in a way that doesn't confirm success or failure. This is not the same as `FAILED`. It means the engine genuinely does not know what happened.
- `REVERSED`: a previously `CONFIRMED` settlement was reversed, either by the external system or by a manual correction after reconciliation

State transitions are one-directional except through explicit reconciliation. `PENDING` can move to `CONFIRMED`, `FAILED`, or `UNKNOWN`. Only `UNKNOWN` can move to `CONFIRMED` or `FAILED` after reconciliation resolves it. Nothing skips `UNKNOWN` back to a confident state without evidence from reconciliation.

## Reconciliation process

`UNKNOWN` settlements, and in fact all settlements, get checked against the external system's own record of truth on a schedule, not just left to sit.

- A scheduled job pulls the external system's transaction history for the relevant period (bank feed, accounting platform API, provider statement).
- Each internal settlement gets matched against an external record using a shared reference (external transaction ID where available, or amount plus timestamp plus account matching when it isn't).
- Matches confirm or correct the internal state.
- Records with no external match after a defined grace period get flagged as mismatches for manual review, not auto-resolved silently.
- Every reconciliation run is logged: what was checked, what matched, what didn't, and what action was taken.

This is what actually catches the cases idempotency alone can't fix, like an external system that confirmed a payment on its side but the confirmation webhook never reached this platform.

## Applied to invoice financing

- A financed invoice's repayment is not assumed just because the due date passed. The reconciliation job checks the business's bank feed or accounting platform for an actual incoming payment matching the invoice.
- If a repayment is detected but doesn't match the expected amount, that's a mismatch, not an automatic resolution. It gets flagged.
- If no repayment is detected past the due date plus a grace period, the invoice moves to overdue and a separate workflow handles collection, not the reconciliation engine itself.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Idempotency key and ledger write in the same transaction | Prevents a race where a retry sees no stored key and reprocesses a request that actually succeeded, this is the single most common way idempotency implementations fail in practice |
| 2026-07-11 | `UNKNOWN` as a first-class state, not treated as `FAILED` | Treating a timeout as failure risks a false retry that double-pays, treating it as success risks silently losing a transfer, `UNKNOWN` forces the system to resolve it with evidence instead of guessing |
| 2026-07-11 | Reconciliation runs on a schedule, not only reactively | Some failures never produce a callback or webhook at all, a scheduled sweep is the only way to catch those |
| 2026-07-11 | Mismatches require manual review by default, no silent auto-resolution | Auto-resolving a financial discrepancy without evidence is how real money gets lost quietly, this project treats that as unacceptable even at portfolio scale |
| 2026-07-11 | Two transactional boundaries implemented in Phase 1: reserve-key-plus-PENDING, and finalize-plus-ledger-plus-key-completion, each atomic on its own, in a bean separate from the orchestrator | Matches the "idempotency key and ledger write in the same transaction" rule above precisely at each step, while avoiding Spring's self-invocation pitfall where `@Transactional` silently no-ops on a same-class method call |
| 2026-07-11 | Concurrent insert races on the same idempotency key are recovered by catching `DataAccessException` broadly, not just `DataIntegrityViolationException` | Verified empirically against real Postgres (Testcontainers): concurrent inserts targeting the same unique key surfaced sometimes as a clean unique-violation and sometimes as a Postgres deadlock between the competing index insertions, depending on timing. Both mean the same thing operationally and both must fall back to reading the winning record |
| 2026-07-11 | The winner's own finalize step gets a bounded retry (not a fallback-read) on `TransientDataAccessException` | A second, distinct deadlock was found empirically: the single winning thread's terminal-state update on `idempotency_keys` can deadlock against other (losing) transactions still holding an FK-check share lock on that same row from their own doomed `settlements` insert. Unlike the insert race, there is no "other record" to fall back to here — the fix is a plain retry of just the finalize step, since the settlement is already durably `PENDING` |

## Open questions

- Exact grace period before an unmatched settlement gets flagged as a mismatch. Decide once reconciliation is implemented and tested against realistic external delay patterns.
- Whether reconciliation matching uses exact reference IDs only, or falls back to fuzzy matching (amount plus date plus account) when the external system doesn't return a clean reference. Likely need both, exact match first, fuzzy as fallback.
