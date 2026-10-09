# Fraud Detection

## Purpose

Describes the ML service that scores invoice financing requests for fraud risk: its features, model, and how it connects to the rest of the system.

## Current state

Built (Phase 5.5), as a synchronous rule-based scorer called from the backend before disbursement:

- `ml-service/main.py`: a small FastAPI app with two endpoints, `GET /health` and `POST /score`. No persistence of its own and no Kafka involvement — see "Sync vs async" below for why.
- Rules (each additive, capped at 1.0 total):
  - `duplicate_customer_reference_count > 0` → `+0.4`, reason `duplicate_customer_reference` (same customer reference already invoiced by a *different* business account)
  - `business_account_age_days < 7 and requested_advance_amount > 500.0` → `+0.3`, reason `new_account_high_advance`
  - `outstanding_advance_count >= 3` → `+0.3`, reason `rapid_refinancing` (business already has 3+ invoices in `FINANCED`/`OVERDUE`)
  - `decision = BLOCK` if `score >= 0.7`, else `ALLOW`
- Java side: `InvoiceService.financeInvoice` computes the request features (via new `InvoiceRepository` count queries and `LedgerAccount.getCreatedAt()`), calls `HttpFraudDetectionClient` (`com.settlementengine.core.fraud`), persists the outcome to `fraud_assessments` (Flyway V6) regardless of decision, and throws `FraudBlockedException` (mapped to `422`) *before* the invoice is claimed for financing if the decision is `BLOCK` — so a blocked invoice is left untouched in `ISSUED`, not stuck half-claimed.
- Config: `settlement-engine.fraud-detection.base-url` (default `http://localhost:8000`) and `.timeout-ms` (default `2000`).

## Sync vs async

The original plan ("consumes `settlement.requested` events from Kafka" but also "consumed by backend before an advance is disbursed") was self-contradictory: the outbox-polling delay plus Kafka consumer lag means a `settlement.requested` event is often still in flight, or the settlement already `CONFIRMED`, by the time an async consumer would see it — too late to block disbursement. Resolved by making the fraud check a synchronous HTTP call from `InvoiceService.financeInvoice`, made *before* the disbursement settlement (and before the invoice is even claimed). No Kafka involvement for fraud checks at all.

## Fail-open

The ML service is advisory, not load-bearing. If it's unreachable, times out, or returns a non-2xx response, `HttpFraudDetectionClient` catches `RestClientException`, logs a warning, and returns an "allow, no signal" result (score `0.0`, decision `ALLOW`, empty reasons) rather than blocking financing. Reasoning: an optional ML service should not become a hard single point of failure for the core financing flow. Side effect (by design, not a bug): every Java test that doesn't run the Python service naturally exercises the fail-open path with no special "disabled" flag needed. One consequence worth knowing: a previously `BLOCK`ed invoice can succeed on retry if the ML service happens to be down for that later attempt — inherent to fail-open, not idempotency-key reuse (financing retries always get a fresh idempotency key per invoice attempt).

## Training data

- Initial validation: Kaggle Credit Card Fraud Detection dataset (ULB), 284,807 transactions, 492 labeled fraud, used to validate the scoring pipeline mechanics even though the fraud patterns differ from invoice fraud
- Production-shaped data: a synthetic generator matching this project's actual invoice and advance schema, built in Phase 5.5, modeling invoice fraud patterns specifically rather than card fraud

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Start with rules, add ML after | Proves the pipeline works before adding model complexity |
| 2026-07-11 | Fraud scoring targets invoice financing specifically, not generic transaction fraud | The niche shifted to invoice financing, fraud patterns for fabricated or duplicated invoices are different from card-present fraud, the Kaggle dataset validates pipeline mechanics only |
| 2026-07-12 | Fraud check is a synchronous HTTP call, not an async Kafka consumer | The original doc's plan was self-contradictory (async consumption can't block a synchronous disbursement decision reliably) — see "Sync vs async" above |
| 2026-07-12 | Fail open (allow) on any fraud-service failure, rather than fail closed | An optional advisory service shouldn't be a hard dependency for the core financing flow; also lets existing Java tests exercise a real code path (fail-open) instead of needing a mock/flag |
| 2026-07-12 | Fraud check runs *before* `InvoiceTransactions.claimForFinancing`, using a plain (non-locked) read of the invoice | `claimForFinancing` transitions `ISSUED`→`FINANCED` and there's no reverse transition in the state machine — running the fraud check first means a `BLOCK` never leaves the invoice in a stuck intermediate state |
| 2026-07-12 | JDK `HttpClient` forced to `HTTP_1_1` in `HttpFraudDetectionClient` | Found manually: the JDK client's default HTTP/2-with-cleartext-upgrade attempt (`Upgrade: h2c` header) confused uvicorn's HTTP/1.1 (h11) parser into treating the POST body as missing, so every real fraud check silently failed open with a `422` from FastAPI ("Field required", `input: null`) even though the JDK-`HttpServer`-backed unit tests passed. Forcing HTTP/1.1 fixed it; see `testing.md` |

## Open questions

- Model retraining cadence, once real usage patterns exist.
- The rule thresholds (0.4 / 0.3 / 0.3, block at 0.7) are placeholders with no historical data behind them yet.
