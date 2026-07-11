# Fraud Detection

## Purpose

Describes the ML service that scores invoice financing requests for fraud risk: its features, model, and how it connects to the rest of the system.

## Current state

Not yet built. Planned for Phase 5.5:

- Python service using FastAPI, consumes `settlement.requested` events from Kafka for financing disbursements
- Starts as a rule-based scorer, upgrades to a trained classifier once the pipeline works end to end
- Invoice financing specific patterns to check: duplicate invoices submitted across different businesses, invoices from newly created business accounts requesting unusually high advances, mismatched invoice metadata (customer name, amount) against prior submissions, rapid repeated financing requests before a previous advance is repaid
- Publishes results as a score consumed by backend before an advance is disbursed

## Training data

- Initial validation: Kaggle Credit Card Fraud Detection dataset (ULB), 284,807 transactions, 492 labeled fraud, used to validate the scoring pipeline mechanics even though the fraud patterns differ from invoice fraud
- Production-shaped data: a synthetic generator matching this project's actual invoice and advance schema, built in Phase 5.5, modeling invoice fraud patterns specifically rather than card fraud

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Start with rules, add ML after | Proves the pipeline works before adding model complexity |
| 2026-07-11 | Fraud scoring targets invoice financing specifically, not generic transaction fraud | The niche shifted to invoice financing, fraud patterns for fabricated or duplicated invoices are different from card-present fraud, the Kaggle dataset validates pipeline mechanics only |

## Open questions

- Model retraining cadence, once real usage patterns exist.
