# Idempotent Settlement and Reconciliation Engine

## What this is

A ledger core that guarantees correct money movement on top of unreliable networks: at-least-once delivery, retries, timeouts, and external systems that don't always confirm cleanly. The core is idempotent by design and reconciles itself against external sources of truth on a schedule. Invoice financing is the first application built on top of it.

## The underlying problem

Every fintech product that moves money shares one problem underneath the surface features: it holds a ledger that has to stay consistent with an external system it doesn't control. That external system communicates asynchronously, sometimes fails silently, sometimes reports success when nothing happened, and sometimes reports failure after the money already moved.

The real engineering problem is guaranteeing exactly-once money movement on top of a network that only guarantees at-least-once delivery, without double-paying, losing funds, or getting stuck not knowing what happened.

This project builds that core directly, instead of hiding it inside a specific product. The core doesn't know or care what the external system is. It guarantees:

- Every settlement request is idempotent. The same request submitted twice produces one result, not two.
- Every settlement has an explicit state: `PENDING`, `CONFIRMED`, `FAILED`, `UNKNOWN`, `REVERSED`. Nothing is assumed to have succeeded just because a call returned.
- A reconciliation process runs against the external system's actual records and catches drift between what the ledger believes and what actually happened.
- Anything the core can't resolve on its own lands in `UNKNOWN` and gets surfaced, not silently retried forever or silently dropped.

## The application: invoice financing

A business submits an invoice it's owed by a customer. This platform advances a percentage of that invoice's value up front, then collects the full amount when the customer actually pays, and reconciles that repayment against the invoice's real status pulled from an external source (a bank feed or accounting platform).

This is a direct fit for the core problem: the platform's ledger says an invoice was financed and should be repaid, but whether it actually got repaid depends entirely on an external system, the business's bank account or accounting software, that reports asynchronously and isn't always timely or accurate.

## Why this project exists

This is a learning and portfolio project. It targets what actually gets tested at fintech companies: idempotency, state machines for uncertain outcomes, reconciliation against external systems, distributed consistency, secure access control, observability, and CI/CD for production systems.

## Tech stack

- Backend: Spring Boot (Java)
- Database: PostgreSQL
- Cache: Redis
- Messaging: Kafka
- ML service: Python, FastAPI
- Storage: AWS S3
- Frontend: Next.js
- Containers: Docker, Kubernetes
- CI/CD: GitHub Actions
- Observability: Prometheus, Grafana, OpenTelemetry
- Cloud: AWS

## Project structure

```
settlement-engine/
  PROJECT.md              <- this file, master guide
  docs/
    architecture.md        <- system design and data flow
    reconciliation.md       <- the core engine: idempotency, state machine, reconciliation logic
    database.md              <- schema, migrations, indexing decisions
    backend.md                 <- Spring Boot service structure and APIs
    security.md                <- auth, RBAC, access control
    kafka-events.md              <- topics, event schemas, consumers/producers
    fraud-detection.md            <- ML service, features, model choices
    storage.md                     <- S3 structure, presigned URLs, audit logs
    aws-setup.md                    <- AWS account setup and security baseline
    kubernetes.md                    <- cluster layout, deployments, scaling
    observability.md                  <- metrics, logs, traces, dashboards
    frontend.md                        <- Next.js app structure and pages
    cicd.md                             <- pipelines, environments, deployment
    testing.md                          <- test strategy, coverage rules
    DOCS_MAINTENANCE.md                  <- rules for keeping these docs current
  backend/                                <- Spring Boot source
  ml-service/                              <- Python fraud detection source
  frontend/                                 <- Next.js source
  infra/                                     <- Docker, Kubernetes manifests, GitHub Actions, AWS configs
```

## Running the application

Prerequisites: Java 21, Docker Desktop running (needed for local Postgres and for the Testcontainers integration tests — check `docker info` if unsure it's up).

**Local Postgres and Kafka:**

```
docker compose -f infra/docker-compose.yml up -d
```

Starts Postgres on `localhost:5432` (db/user/password all `settlement_engine`, matching `src/main/resources/application.yml` defaults) and a single-node Kafka broker in KRaft mode (no Zookeeper) reachable at `localhost:29092`. If port 5432 or 29092 is already taken locally, don't edit the compose file — run a throwaway container on a different host port instead and point the app at it via `SETTLEMENT_DB_URL` / `KAFKA_BOOTSTRAP_SERVERS` (see below).

**Run the app:**

```
./mvnw spring-boot:run
```

`./mvnw` is a thin wrapper pointing at a Maven distribution already cached locally under `~/.m2/wrapper/dists` — there is no system-wide `mvn` on this machine, don't try to `brew install` one. The app starts on port 8080. To point at a non-default database or Kafka broker, set env vars before running: `SETTLEMENT_DB_URL`, `SETTLEMENT_DB_USER`, `SETTLEMENT_DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`.

**As of Phase 5, `INVOICE_PLATFORM_ACCOUNT_ID` is required** (no default — the app fails fast at startup if it's missing, on purpose, see `database.md`). It must be a real `ledger_accounts.id` seeded ahead of time, representing the account invoice advances are disbursed from and repayments collected into, e.g.:

```
INVOICE_PLATFORM_ACCOUNT_ID=$(uuidgen)
docker exec -it infra-postgres-1 psql -U settlement_engine -d settlement_engine -c \
  "insert into ledger_accounts (id, owner_id, balance, currency, version, created_at) values ('$INVOICE_PLATFORM_ACCOUNT_ID', '$(uuidgen)', 1000000.00, 'USD', 0, now());"
SETTLEMENT_DB_URL=... INVOICE_PLATFORM_ACCOUNT_ID=$INVOICE_PLATFORM_ACCOUNT_ID ./mvnw spring-boot:run
```

**Swagger / OpenAPI**, once the app is up:

- Swagger UI: http://localhost:8080/swagger-ui/index.html (or http://localhost:8080/swagger-ui.html, which redirects there)
- Raw OpenAPI JSON: http://localhost:8080/v3/api-docs

As of Phase 2, every endpoint except `/auth/**` requires a JWT — use Swagger's "Authorize" button and paste `Bearer <accessToken>` from a `POST /auth/login` response before calling anything else.

Current limitations: there is no endpoint yet to create `ledger_accounts` or `users` (not in Phase 1/2 scope). Seed both directly in Postgres, generating IDs client-side (the schema deliberately has no DB-side UUID default — see `database.md`) and the password hash with BCrypt, e.g.:

```
docker exec -it infra-postgres-1 psql -U settlement_engine -d settlement_engine -c \
  "insert into ledger_accounts (id, owner_id, balance, currency, version, created_at) values ('$(uuidgen)', '$(uuidgen)', 1000.00, 'USD', 0, now());"

# Password hash example (BCrypt of "password123"), or generate your own via
# a BCryptPasswordEncoder — see security.md for why raw passwords are never stored.
docker exec -it infra-postgres-1 psql -U settlement_engine -d settlement_engine -c \
  "insert into users (id, username, password_hash, role, owner_id, created_at) values \
  ('$(uuidgen)', 'admin1', '\$2a\$10\$jTNzzXt7BIL.8oXRKe8E1.Rm3FnmPcsuHs6WiwYjA/CmRW9Bp4EZK', 'ADMIN', null, now());"
```

Then `POST /auth/login` with `{"username":"admin1","password":"password123"}` to get a token.

**Run tests:**

```
./mvnw test
```

Requires Docker running — the integration suite (including the concurrency test) uses Testcontainers to spin up a real Postgres instance per test class.

Keep this section accurate whenever the run/access process changes — see `docs/DOCS_MAINTENANCE.md`.

## Build phases

1. Core ledger and idempotency layer (Spring Boot, Postgres, TDD): idempotency keys, double-entry ledger, settlement state machine
2. Security and access control (JWT, RBAC)
3. Event-driven layer (Kafka, CQRS read model)
4. Reconciliation engine: scheduled and event-triggered reconciliation against an external source, mismatch detection and resolution workflow
5. Invoice financing application layer on top of the core: invoices, advances, repayment tracking, storage/audit trail (S3)
5.5. Fraud detection service (Python, FastAPI, ML), focused on invoice fraud patterns
6. Containerization and deployment (Docker, Kubernetes, GitHub Actions, AWS)
7. Observability (Prometheus, Grafana, OpenTelemetry tracing)
8. Frontend (Next.js dashboard)
9. Load and correctness testing, including chaos testing for network failure and duplicate delivery scenarios

Current phase: **Phase 1 through Phase 5, done — the core engine and its first application layer are complete. Phase 5.5 (fraud detection) and the infrastructure phases (6-9) not yet started.**

## Repo structure decision

Monorepo. All services (backend, ml-service, frontend, infra) live in one repository.

This follows standard practice for early-stage systems with a small team and tightly coupled services. A monorepo keeps versioning, cross-service changes, and CI/CD coordination simple. Multi-repo makes sense once services scale to separate teams with independent release cycles, which does not apply here. Revisit this if the project grows past a single contributor.

## Status log

Update this section every time a phase starts or finishes. Keep entries short.

| Date | Phase | Status | Notes |
|------|-------|--------|-------|
| 2026-07-11 | Setup | Done | Docs structure created |
| 2026-07-11 | Setup | Done | Repo structure decided: monorepo |
| 2026-07-11 | Setup | Done | Reframed core problem: idempotent settlement and reconciliation engine |
| 2026-07-11 | Setup | Done | Application layer set: invoice financing |
| 2026-07-11 | Setup | Done | Added Kubernetes and observability (Prometheus, Grafana, OpenTelemetry) to stack |
| 2026-07-11 | Phase 1 | Done | Spring Boot project scaffolded (Spring Boot 3.5.5, Java 21); Flyway migration for core schema (`ledger_accounts`, `idempotency_keys`, `settlements`, `ledger_entries`) |
| 2026-07-11 | Phase 1 | Done | Domain layer built with TDD: `SettlementStatus` state machine, `LedgerAccount` debit/credit, `Settlement`, `IdempotencyKey` entities and their invariants |
| 2026-07-11 | Phase 1 | Done | `SettlementService`/`SettlementTransactions` built: idempotent settlement creation, external gateway seam (`ExternalSettlementGateway`, mock implementation), double-entry ledger writes on `CONFIRMED` only |
| 2026-07-11 | Phase 1 | Done | REST API built: `POST /settlements`, `GET /settlements/{id}`, `GET /accounts/{id}`, centralized exception mapping |
| 2026-07-11 | Phase 1 | Done | Full test suite (51 tests): unit tests for all business logic plus Testcontainers integration tests, including a concurrency test that proves exactly-once settlement creation against real Postgres under 8 concurrent threads |
| 2026-07-11 | Phase 1 | Fixed | Schema/entity mismatch: currency columns were `CHAR(3)`, Hibernate schema validation expects `VARCHAR(3)` — migration fixed, caught by the Testcontainers tests before it ever reached a real environment |
| 2026-07-11 | Phase 1 | Fixed | Concurrent inserts on the same idempotency key can surface as a clean unique-violation or a Postgres deadlock depending on timing — race-fallback broadened from `DataIntegrityViolationException` to `DataAccessException` to catch both |
| 2026-07-11 | Phase 1 | Fixed | Second deadlock found under the same concurrency test: the winning thread's own finalize step can deadlock against losing transactions' FK-check locks on `idempotency_keys` — fixed with a bounded retry (5 attempts) on `TransientDataAccessException`, scoped to just the finalize step |
| 2026-07-11 | Phase 1 polish | Done | springdoc-openapi wired in: `/v3/api-docs` and Swagger UI at `/swagger-ui/index.html`, with `@Operation`/`@ApiResponse`/`@Schema` annotations on both controllers and `CreateSettlementRequest`; manually verified by starting the app and checking the generated docs |
| 2026-07-11 | Phase 2 | Done | Schema: `users`, `refresh_tokens`, `audit_log` (Flyway V2) |
| 2026-07-11 | Phase 2 | Done | JWT auth built with TDD: `JwtService` (issue/parse, HMAC-signed, role + optional ownerId claims), `RefreshTokenService` (rotation, hashed storage), `AuthService`/`AuthController` (`POST /auth/login`, `/refresh`, `/logout`) |
| 2026-07-11 | Phase 2 | Done | `SecurityConfig` + `JwtAuthenticationFilter`: stateless JWT auth on every endpoint except `/auth/**` and Swagger/OpenAPI paths; uniform JSON error bodies for 401/403 |
| 2026-07-11 | Phase 2 | Done | Role checks: `POST /settlements` requires `ADMIN`/`SUPPORT` (`@PreAuthorize`) |
| 2026-07-11 | Phase 2 | Done | Row-level ownership: `RowLevelAccessGuard` restricts `READ_ONLY` users to accounts/settlements tied to their own `owner_id`; `ADMIN`/`SUPPORT` unrestricted |
| 2026-07-11 | Phase 2 | Done | Audit logging: login attempts and account/settlement GET/create access attempts recorded to `audit_log` with `SUCCESS`/`DENIED`/`FAILURE` outcome (known gap: `@PreAuthorize` role denials aren't yet captured — see `security.md`) |
| 2026-07-11 | Phase 2 | Done | Full test suite (79 tests): JWT issuing/parsing, refresh rotation, row-level guard, auth service, plus updated integration tests exercising the full login → protected-endpoint → refresh → logout flow against real Postgres; also manually verified end-to-end against a running instance |
| 2026-07-11 | Phase 2 | Fixed | Spring Boot's default `UserDetailsServiceAutoConfiguration` was generating an unused random dev password on every startup since no `UserDetailsService` bean exists (auth is entirely JWT-based) — excluded explicitly |
| 2026-07-11 | Phase 3 | Done | Schema: `outbox_events`, `settlement_read_model` (Flyway V3); Kafka broker (KRaft, no Zookeeper) added to `infra/docker-compose.yml` |
| 2026-07-11 | Phase 3 | Done | Transactional outbox built with TDD: `OutboxEvent`/`OutboxWriter` write `settlement.requested`/`confirmed`/`failed`/`unknown` events in the same transaction as the settlement state change; `OutboxPublisher` (scheduled poll) sends to Kafka and only marks published after a confirmed ack |
| 2026-07-11 | Phase 3 | Done | CQRS read model built: `SettlementEventConsumer` upserts `settlement_read_model` off all four topics |
| 2026-07-11 | Phase 3 | Done | Full test suite (92 tests): unit tests for outbox/publisher/consumer plus a Testcontainers integration test proving the full DB write → outbox → Kafka → consumer → read model loop against a real broker; also manually verified end-to-end against a running instance with real scheduled timing (no manual triggers) |
| 2026-07-11 | Phase 3 | Fixed | Assumed same partition key gives ordering across topics — it doesn't; Kafka only orders within one topic-partition, so `settlement.confirmed` was observed arriving before `settlement.requested`. Fixed: every consumer handler upserts, with a last-write-wins check by event timestamp so a late `requested` can't downgrade an already-`CONFIRMED` row |
| 2026-07-11 | Phase 3 | Fixed | Second race found under the same integration test: two different topics' consumer threads racing to insert the same settlement's read-model row for the first time. Fixed with the same insert-race-and-fallback pattern already used in `SettlementService` |
| 2026-07-11 | Phase 4 | Done | Gateway refactored to return an external reference alongside its outcome (`GatewayResult`); `Settlement.externalRef` now actually gets populated, which reconciliation needs to match by reference |
| 2026-07-11 | Phase 4 | Done | `MockExternalSystem` built: one component playing both `ExternalSettlementGateway` and `ExternalReconciliationSource`, backed by a shared in-memory record per reference, with `forget`/`corrupt` test hooks to inject drift |
| 2026-07-11 | Phase 4 | Done | Schema: `reconciliation_runs`, `reconciliation_mismatches` (Flyway V4) |
| 2026-07-11 | Phase 4 | Done | `ReconciliationService` built with TDD (12 tests covering every branch): exact-reference matching, grace-period-aware no-match handling, `UNKNOWN` auto-resolution (reusing `SettlementTransactions.finalizeSettlement`), mismatch flagging with dedup, per-settlement error isolation so one bad record doesn't fail the whole run |
| 2026-07-11 | Phase 4 | Done | `ReconciliationScheduler` (60s default) plus `POST /reconciliation/runs`, `GET /reconciliation/mismatches`, `POST /reconciliation/mismatches/{id}/resolve` (all `ADMIN`/`SUPPORT` only), with audit logging extended to these actions |
| 2026-07-11 | Phase 4 | Done | Full test suite (116 tests): reconciliation unit + integration tests proving both the auto-resolve-updates-the-ledger path and the mismatch-flagged-not-auto-resolved path against real Postgres; also manually verified end-to-end against a running instance (settlement → reconciliation run → matched, plus role enforcement and Swagger listing) |
| 2026-07-11 | Phase 4 | Fixed | `AbstractIntegrationTest`'s shared static Postgres container field, combined with Spring's test context caching, could hand a test class a cached context pointing at an already-dead container port after another class's container restarted — added `@DirtiesContext(classMode = AFTER_CLASS)` |
| 2026-07-12 | Phase 5 | Done | Schema: `invoices`, `advances` (Flyway V5), referencing `ledger_accounts`/`settlements` directly rather than inventing a separate business entity |
| 2026-07-12 | Phase 5 | Done | `InvoiceService`/`InvoiceTransactions` built with TDD: invoice submission, financing (80%/2% advance/fee defaults) disbursed through the *same* `SettlementService` every other money movement uses, with a pessimistic-lock "claim" step closing a double-financing race (see below) |
| 2026-07-12 | Phase 5 | Done | `InvoiceRepaymentService` + scheduler (60s default): checks a mock `InvoicePaymentSource` for customer payments, collects repayment via a settlement keyed by a deterministic idempotency key, marks invoices `OVERDUE` past due date + grace period (3 days default) |
| 2026-07-12 | Phase 5 | Done | REST endpoints: `POST /invoices`, `POST /invoices/{id}/finance`, `GET /invoices/{id}` (row-level restricted like accounts/settlements), audit-logged |
| 2026-07-12 | Phase 5 | Done | Full test suite (170 tests): invoice/advance domain, service, and repayment-matching unit tests plus a Testcontainers integration test proving the full submit → finance → detect payment → repay lifecycle with correct ledger balances throughout; also manually verified end-to-end against a running instance (repayment path verified only via the automated test, same reasoning as Phase 4's mismatch scenario — there's no REST-exposed way to simulate a customer payment, by design) |
| 2026-07-12 | Phase 5 | Fixed | Found while designing financing: concurrent finance requests for the same invoice with *different* idempotency keys aren't caught by the settlement engine's own idempotency mechanism, so an invoice could be double-financed. Fixed with a pessimistic row lock that atomically claims the invoice (`ISSUED`→`FINANCED`) before the disbursement is even attempted |
| 2026-07-12 | Phase 5 | Fixed | Adding a required (`no default`) `INVOICE_PLATFORM_ACCOUNT_ID` property broke every existing `@SpringBootTest`, since they all boot the full context including `InvoiceService`'s bean definition. Fixed with `src/test/resources/application-test.yml` + `@ActiveProfiles("test")` on `AbstractIntegrationTest`, rather than duplicating the whole config file |

## Rules for working on this project

- TDD is not optional. Write the failing test before the implementation for every new piece of business logic.
- Every component doc in `docs/` must be updated in the same session as the code change it describes. Do not batch doc updates for later.
- No secrets, API keys, or credentials in any file. Use environment variables and reference them by name only.
- Every new Kafka topic gets an entry in `kafka-events.md` before the first producer is written.
- Every new endpoint gets an entry in `backend.md` and a security note in `security.md` if it touches account data.
- No settlement logic gets written without an idempotency key check and an explicit state transition. See `reconciliation.md` before touching any money-movement code.
- See `docs/DOCS_MAINTENANCE.md` for the full doc update process.
