# Load testing (Phase 9)

k6 script exercising `POST /settlements` and `POST /invoices/{id}/finance` under concurrent load
against the **local dev stack only** -- never the shared staging server, see `docs/testing.md`'s
decisions log for why.

## Setup

1. `docker compose -f infra/docker-compose.yml up -d` (Postgres + Kafka)
2. Seed the admin user and the platform account per `PROJECT.md`'s "Running the application", then seed the load test's own accounts:
   ```
   docker exec -i infra-postgres-1 psql -U settlement_engine -d settlement_engine < load/seed-accounts.sql
   ```
3. `./mvnw spring-boot:run`
4. Install k6 if you don't have it: `brew install k6` (or see https://k6.io/docs/get-started/installation/)

## Run

```
k6 run load/settlement-load-test.js
```

Three scenarios run back to back: a burst of fresh settlements across a pool of accounts, a
concurrent burst all reusing one idempotency key against a dedicated account pair, and a slice of
invoice-submit-then-finance requests. `teardown()` checks the pool's total balance is unchanged
(money only moves within it, never created or destroyed) and that the duplicate-key pair moved by
exactly one settlement's amount, not once per concurrent request -- the actual proof that no
double-payment happened under real concurrent load, not just k6's own per-request status checks.
