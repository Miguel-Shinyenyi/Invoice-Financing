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

## The Lab runner versus k6

The Lab (`docs/lab.md`, `/lab/load`) has its own runner, `LabLoadDriver`, that reproduces the same three scenarios
(fresh settlements across the 6-account pool, a duplicate-key burst on the dedicated pair, invoice-submit-then-finance)
and checks the same invariants at the end. It is a different driver, so the numbers will differ.

| | k6 (`settlement-load-test.js`) | Lab runner |
| --- | --- | --- |
| Where it runs | A separate process, on your machine or another host | A JVM thread pool inside the backend's own process, same host and CPU |
| Requests | Real HTTP to `BASE_URL` | Real HTTP to the app's own port on loopback, with the sandbox ADMIN token, so the security chain, `RequestIdFilter`, audit logging and JSON serialization are in the path |
| Load shape | Fixed scenarios, 20 s / 20 iterations / 15 iterations | One scenario per run: 1 to 20 virtual users, up to 30 s and 5000 requests, set by the visitor within server-side caps |
| Latency | k6's own client-side timings | Measured per request with `System.nanoTime` around the JDK `HttpClient`, recorded in a Micrometer histogram (p50/p95/p99) |
| Invariants | Pool total, duplicate-key source and destination (absolute balances) | Pool total, duplicate-key pair (relative to a baseline, with a per-run key), every touched balance equals its entries, no stale PENDING, outbox drained, read model caught up |
| Faults | None | Optional gateway fault profile (for example 5% response lost) |

Which to quote: k6. It runs outside the process under test, so it does not compete with the backend for CPU, and its
numbers are the ones to compare against another deployment. The Lab runner shows the same behaviour live and the same
invariants, but its latency includes the cost of sharing a machine with the thing being measured.

The Lab's duplicate-key check is relative (the pair moved by exactly one settlement's amount, or by nothing if that one
settlement did not confirm) because a sandbox can be dirty from an earlier run; k6's check assumes a freshly seeded pair.
