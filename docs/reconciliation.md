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

Built (Phase 4). `ReconciliationService.runOnce()` checks every settlement the gateway has ever produced an external reference for — `SettlementRepository.findByExternalRefIsNotNull()` — against the external system's own record of truth, on a schedule (`ReconciliationScheduler`, `@Scheduled`, 60s default) and on demand (`POST /reconciliation/runs`).

- **Matching by reference, exact only.** Each settlement is looked up in `ExternalReconciliationSource` by its `externalRef`. Fuzzy matching (amount + timestamp + account, for when no clean reference exists) is not built — every path in this codebase that produces a settlement also produces a reference, so there's been no real case yet that needs it. Deferred; see Open Questions.
- **No external record found**: if less than the grace period (`settlement-engine.reconciliation.grace-period-seconds`, 300s default) has passed since the settlement's `updatedAt`, it's too soon to conclude anything — skipped this run, not flagged. Past the grace period, it's a mismatch (`external_state` recorded as `null`).
- **External record found, amount or currency differs**: always a mismatch, regardless of status, even for an `UNKNOWN` settlement — a numbers disagreement isn't safe to paper over with an evidence-based auto-resolution.
- **External record found, amounts agree, settlement is `UNKNOWN`**: auto-resolved. This is the one case reconciliation is allowed to change settlement state on its own (per the state machine above) — it calls the exact same `SettlementTransactions.finalizeSettlement` used by the original settlement flow, so ledger entries, the idempotency key's cached response, and the `settlement.confirmed`/`settlement.failed` outbox event all get written exactly the way they would have if the gateway had answered promptly. A `reconciliation.resolved` event is published alongside.
- **External record found, amounts agree, settlement already `CONFIRMED`/`FAILED`**: if the external status agrees, nothing happens (already correct). If it disagrees, that's a mismatch — never auto-resolved, since money may have already moved internally and only a human can judge what to do about a `CONFIRMED` settlement the external system now disputes.
- **Mismatches are deduplicated** by settlement: an already-`OPEN` mismatch for the same settlement isn't duplicated on a later run, though it's still counted in that run's `mismatchesFound`.
- Every run is logged as a `reconciliation_runs` row: started/finished time, records checked, mismatches found, status (`RUNNING` → `COMPLETED`/`FAILED`). One settlement erroring out is logged and skipped, not fatal to the run; the whole run only becomes `FAILED` if it can't even load the candidate list.

This is what actually catches the cases idempotency alone can't fix, like an external system that confirmed a payment on its side but the confirmation webhook never reached this platform.

### The external system, for now

There's no real bank/provider integration yet, so `MockExternalSystem` plays both roles a real one would: it's the `ExternalSettlementGateway` settlements are confirmed through, and the `ExternalReconciliationSource` reconciliation checks against, backed by one shared in-memory record per reference. By default everything reconciles cleanly, because it's the same fake system on both sides of the check — this is deliberate, so the common case demonstrates a real match rather than needing to be faked. `forget(ref)` and `corrupt(ref, record)` let tests (and would let a demo) inject the exact kind of drift reconciliation exists to catch.

## Applied to invoice financing

Built (Phase 5), as its own `InvoiceRepaymentService` — parallel to `ReconciliationService` in shape (scheduled, one-bad-record-doesn't-stop-the-batch, mismatches not auto-resolved), but a separate class, because it checks *invoices* for customer payments, not *settlements* for external agreement; the two aren't the same query or the same external source.

- A financed invoice's repayment is not assumed just because the due date passed. `InvoiceRepaymentService.checkRepayments()` checks `InvoicePaymentSource` (mocked for now, same reasoning as `MockExternalSystem` above) for an actual incoming payment matching the invoice's `externalSourceRef`.
- If a repayment is detected but doesn't match the expected amount exactly, that's not resolved automatically — logged and skipped, left `FINANCED` for a human to look at. Unlike settlement mismatches, this isn't written to `reconciliation_mismatches` (that table's FK to `settlements` doesn't fit an invoice-level discrepancy that hasn't produced a settlement yet); it's a narrower, log-only version of the same principle. See Open Questions.
- If no repayment is detected past the due date plus a grace period (`settlement-engine.invoice-financing.repayment-grace-period-days`, 3 days default), the invoice moves to `OVERDUE`. Nothing further happens automatically — a separate collection workflow, not built, would take over.
- When a matching repayment *is* detected, collecting it is a real settlement (business account → platform account, `amountAdvanced + fee`) through the same engine as everything else, using a **deterministic idempotency key** derived from the invoice id (`UUID.nameUUIDFromBytes("invoice-repayment-" + invoiceId)`). This is what makes it safe for the scheduler to call this repeatedly across runs: a repeat call for an invoice whose repayment settlement already resolved just returns the cached result: If the settlement first came back `UNKNOWN`, a later run's identical call returns whatever Phase 4's reconciliation engine has since resolved it to — no new code needed for that interaction, it falls out of idempotency key reuse plus the existing reconciliation engine.

**Known simplification**: nothing in this system credits a business's ledger balance when their external bank account receives the customer's payment. Repayment collection assumes the business's `ledger_accounts` balance already covers `amountAdvanced + fee` (e.g. from the advance itself, if the advance rate leaves enough margin, or from other funds). Modeling "the business got paid externally, therefore their ledger balance should increase" would need a distinct deposit concept this project doesn't have. If the business's balance is actually insufficient at collection time, that's a real `InsufficientBalanceException` from the settlement engine — a correctness-preserving failure, not a swallowed one, just not one this phase tries to solve.

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
| 2026-07-11 | Grace period defaults to 300 seconds (`settlement-engine.reconciliation.grace-period-seconds`) | Resolves the open question below. Long enough that a settlement isn't wrongly flagged before the external system has plausibly had a chance to reflect it; short enough to keep review latency reasonable. No real external system yet to benchmark against, so this is a starting default, not a measured one — revisit once one exists |
| 2026-07-11 | Exact-reference matching only for Phase 4, no fuzzy fallback | Resolves the other open question below. Every settlement that reaches reconciliation already has an `externalRef` (the gateway always produces one), so there has been no real case yet needing amount+date+account fuzzy matching. Deferred rather than built speculatively |
| 2026-07-11 | Auto-resolution of an `UNKNOWN` settlement reuses `SettlementTransactions.finalizeSettlement` rather than a separate code path | The reconciliation engine and the original settlement flow must never be able to disagree about how a settlement becomes `CONFIRMED`/`FAILED` — reusing the one method that writes ledger entries, completes the idempotency key, and publishes the outbox event guarantees that, and was already safely reusable since `UNKNOWN → CONFIRMED/FAILED` is a transition the state machine already allowed |
| 2026-07-11 | `ReconciliationService.runOnce()` is not wrapped in one outer `@Transactional` | Each settlement's resolution or mismatch write is already its own atomic unit (via `SettlementTransactions` or a single repository save); one long-running transaction spanning every candidate settlement would hold DB resources far longer than any individual write needs, which matters once there are many settlements to check per run |
| 2026-07-11 | A settlement resolved by another process between being fetched and being finalized (`IllegalStateTransitionException` from a no-longer-valid transition) is logged and skipped, not fatal to the run | Defensive: two reconciliation runs overlapping, or reconciliation racing a real external confirmation arriving through another path, shouldn't be able to abort the whole batch over one settlement that's already been handled |
| 2026-07-12 | `InvoiceRepaymentService` is a separate class from `ReconciliationService`, not a mode of it | They check different things against different sources (settlements vs. externally-reported invoice payments) with different candidate queries; forcing one into the other's shape would need a generic abstraction neither actually needs yet |
| 2026-07-12 | Repayment collection uses a deterministic idempotency key (`UUID.nameUUIDFromBytes` over the invoice id), not a random one per attempt | The scheduler calls `checkRepayments()` repeatedly forever; a random key per call would let an `UNKNOWN` or slow-to-resolve repayment settlement be reattempted as a *new* settlement every run. A deterministic key makes repeat calls for the same invoice hit the existing idempotency-key cache instead — the same guarantee `SettlementService` already provides, just addressed by a key this caller can reconstruct rather than one it has to remember |
| 2026-07-12 | Invoice-level amount mismatches are logged and skipped, not written to a mismatch table | `reconciliation_mismatches.settlement_id` is `NOT NULL` and an invoice-amount mismatch is detected *before* any repayment settlement exists to reference. Building a parallel invoice-mismatch table was judged out of scope for Phase 5; the gap is narrower than it sounds since a mismatch here just means no settlement gets created, so nothing incorrect propagates, it just doesn't get resolved automatically |

## Open questions

- Fuzzy matching (amount + date + account) for external systems that don't return a clean reference. Not built — revisit when a real external system integration actually needs it.
- The grace period (300s default) and scheduled interval (60s default) are both starting defaults, not measured against a real external system's actual confirmation latency. Revisit once one exists.
- Invoice-level repayment-amount mismatches have no persistent, queryable record (see decisions log) — if this ever needs its own review workflow like settlement mismatches have, it would need either a nullable FK on `reconciliation_mismatches` or a dedicated table.
- Nothing credits a business's ledger balance when their external bank account receives the customer's payment (see the "known simplification" above and `database.md`'s matching open question).
