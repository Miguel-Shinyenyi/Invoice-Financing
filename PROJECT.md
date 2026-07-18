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
    storage.md                     <- object storage structure, presigned URLs, audit logs
    server-setup.md                 <- self-hosted server setup and security baseline (was aws-setup.md)
    kubernetes.md                    <- cluster layout, deployments, scaling
    observability.md                  <- metrics, logs, traces, dashboards
    frontend.md                        <- Next.js app structure and pages
    cicd.md                             <- pipelines, environments, deployment
    testing.md                          <- test strategy, coverage rules
    DOCS_MAINTENANCE.md                  <- rules for keeping these docs current
  backend/                                <- Spring Boot source
  ml-service/                              <- Python fraud detection source
  frontend/                                 <- Next.js source
  infra/                                     <- Docker, Kubernetes manifests, GitHub Actions, server configs
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

`./mvnw` is the standard, official Maven Wrapper (self-downloads Maven 3.9.9 into `~/.m2/wrapper/dists` on first run if it isn't already cached there) — there is no system-wide `mvn` on this machine, don't try to `brew install` one, but `mvnw` itself needs no local setup and works unmodified on any machine, including CI (see the Status log entry fixing a hardcoded-local-path regression here). The app starts on port 8080. To point at a non-default database or Kafka broker, set env vars before running: `SETTLEMENT_DB_URL`, `SETTLEMENT_DB_USER`, `SETTLEMENT_DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`.

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

**Fraud detection service (Phase 5.5), optional but recommended:**

```
cd ml-service
python3 -m venv .venv && source .venv/bin/activate   # first time only
pip install -r requirements-dev.txt                   # first time only
uvicorn main:app --host 0.0.0.0 --port 8000
```

The backend calls this at `http://localhost:8000` by default (`settlement-engine.fraud-detection.base-url` / `FRAUD_DETECTION_BASE_URL` env var to override) before disbursing any advance. It's advisory only: if it's not running, `POST /invoices/{id}/finance` still works, just always with an "allow, no signal" fraud assessment (fail-open, see `docs/fraud-detection.md`) — so it's fine to skip starting it for a quick backend-only check, but start it to see real scores.

**Run tests:**

```
./mvnw test
```

Requires Docker running — the integration suite (including the concurrency test) uses Testcontainers to spin up a real Postgres instance per test class.

The Python `ml-service` has its own test suite, run separately:

```
cd ml-service && source .venv/bin/activate && python -m pytest test_main.py
```

**Deployed instance — staging (Phase 6):**

Runs on a self-hosted bare-metal server (k3s), not AWS. **Every merge to `dev` deploys straight to staging automatically** (`.github/workflows/ci.yml`'s `deploy-staging` job — see `docs/cicd.md`; there's no production environment yet, see that doc's decisions log for why). Setup steps, deploy runbook, and every decision specific to running on a *shared* server are in `docs/server-setup.md`; the Kubernetes manifests are in `infra/k8s/`. No public DNS points at this server yet, so everything below is reached by IP.

**Deployed links (staging, `107.155.122.29`):**

| Service | URL | Notes |
|---------|-----|-------|
| Backend API | `https://107.155.122.29:30443` | Self-signed CA (see `docs/kubernetes.md`'s TLS section) — `curl -k` or trust the CA cert pulled from the `invoice-financing-ca-secret` Secret |
| Swagger UI | `https://107.155.122.29:30443/swagger-ui/index.html` | Same cert as above |
| Frontend (admin dashboard) | `https://107.155.122.29:30443/app` | Same cert/NodePort as the backend, served under the `/app` path prefix — see `docs/frontend.md`'s decisions log for why |
| Grafana | `http://107.155.122.29:30030` | Plain HTTP, no Ingress/TLS. **Default `admin`/`admin` login, not yet changed** — see `docs/observability.md`'s open questions |
| Prometheus | `http://107.155.122.29:30090` | Plain HTTP |
| Alertmanager | `http://107.155.122.29:30093` | Plain HTTP |
| Jaeger UI | `http://107.155.122.29:30686` | Plain HTTP |

None of these except the backend API go through Traefik/cert-manager — the observability UIs are plain NodePorts (see `docs/kubernetes.md`'s decisions log for why). Keep this table accurate whenever a port or service changes — see `docs/DOCS_MAINTENANCE.md`.

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

Current phase: **All 9 planned phases done, deployed, and verified end-to-end on staging.** Phase 9 (load and chaos testing, see `docs/testing.md`) found and fixed two real gaps in `SettlementService`'s handling of gateway failures and deadlock-retry exhaustion, plus a CI rollout-timeout false negative (`docs/cicd.md`). No further phases currently planned — revisit `PROJECT.md`'s Build phases list if new scope is added.

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
| 2026-07-12 | Phase 5.5 | Done | Schema: `fraud_assessments` (Flyway V6) |
| 2026-07-12 | Phase 5.5 | Done | `ml-service/main.py` built with TDD (Python, FastAPI): rule-based `/score` endpoint (duplicate customer reference, new-account-plus-high-advance, rapid refinancing; `BLOCK` at score ≥ 0.7), 9 pytest tests written before the implementation |
| 2026-07-12 | Phase 5.5 | Done | `FraudAssessment` entity/repository and `HttpFraudDetectionClient` (Java, `RestClient` over the JDK `HttpClient`) built with TDD, wired into `InvoiceService.financeInvoice`: fraud check runs before the invoice is claimed for financing, persists an assessment unconditionally, throws `FraudBlockedException` (422) on `BLOCK` |
| 2026-07-12 | Phase 5.5 | Done | Fraud check is synchronous HTTP, not async Kafka consumption, resolving a self-contradiction in the original plan (an async consumer can't reliably block a synchronous disbursement decision); fails open (allows financing) if the ML service is unreachable, since it's advisory, not load-bearing |
| 2026-07-12 | Phase 5.5 | Done | Full test suite (178 Java tests + 9 Python tests): all passing; manually verified end-to-end against running instances of the backend, Postgres, Kafka, and the ml-service — a clean invoice (ALLOW), a combined-signal invoice (BLOCK, 422, invoice left `ISSUED`), and the fail-open path with the ml-service stopped |
| 2026-07-12 | Phase 5.5 | Fixed | The JDK `HttpClient`'s default HTTP/2-cleartext-upgrade attempt (`Upgrade: h2c` header) silently caused uvicorn to treat every real fraud-check POST body as missing (`422`, "Field required"), even though the JDK-`HttpServer`-backed unit tests passed — only caught by manual end-to-end testing against the real ml-service, not the automated suite. Fixed by forcing `HttpClient.Version.HTTP_1_1` in `HttpFraudDetectionClient` |
| 2026-07-17 | Phase 6 | Started | Decided to self-host on a bare-metal Ubuntu server (ssdnodes) with k3s + MinIO instead of AWS (EKS/S3) — no AWS account exists for this project. `docs/aws-setup.md` renamed to `docs/server-setup.md` and rewritten |
| 2026-07-17 | Phase 6 | Found | The server turned out to be shared with other tenants (other accounts, an active Docker Swarm, containers already bound to host 80/443/5000/etc.), not a clean dedicated box as first assumed — every subsequent step scoped to avoid touching anything outside this project's own footprint (see `docs/server-setup.md`'s decisions log) |
| 2026-07-17 | Phase 6 | Done | k3s installed single-node with `--disable=servicelb` and Traefik pinned to fixed NodePorts 30080/30443 (`kubectl patch`), since the default ServiceLB behavior would have fought other tenants for host ports 80/443. Kubeconfig kept root/owner-only (not world-readable), API server (6443) not exposed externally — `kubectl` only from an on-box SSH session |
| 2026-07-17 | Phase 6 | Done | Local Docker registry (`registry:2` on `127.0.0.1:15000`, port 5000 was already taken) wired into k3s containerd as a trusted insecure registry; verified end-to-end with a real push + pod pull |
| 2026-07-17 | Phase 6 | Done | cert-manager v1.21.0 installed with a two-step self-signed CA (bootstrap issuer → root CA cert → CA-type issuer for leaf certs), since no domain exists yet for Let's Encrypt. A clean one-line swap to an ACME issuer later |
| 2026-07-17 | Phase 6 | Done | Multi-stage `Dockerfile`s for both services (Maven/JDK build stage → slim JRE runtime for the backend; `python:3.13-slim` for ml-service), both non-root, both verified by building and running locally before ever touching the server |
| 2026-07-17 | Phase 6 | Done | `infra/k8s/` manifests written for every component (Postgres, Kafka, MinIO, ml-service, backend + Ingress + leaf Certificate), single `invoice-financing` namespace, no staging/production split (superseding the original plan — see `kubernetes.md`/`cicd.md` decisions logs) |
| 2026-07-17 | Phase 6 | Done | `.github/workflows/ci.yml` (test on every push, self-hosted-runner deploy on push to `dev`) plus a GitHub Actions self-hosted runner installed as a systemd service on the box, polling outbound so no inbound port is needed |
| 2026-07-17 | Phase 6 | Done | Full stack deployed and manually verified end-to-end against the live Kubernetes environment over properly CA-verified HTTPS (not just `-k`): login → submit invoice → finance (hit the real deployed ml-service, not fail-open) → confirmed ledger balances, read model, and outbox events all correct |
| 2026-07-17 | Phase 6 | Fixed | Kafka crash-looped on first boot ("channel manager timed out" during controller self-registration) — fixed by routing `KAFKA_CONTROLLER_QUORUM_VOTERS` through `localhost` instead of the k8s Service DNS name (removes unnecessary Service/DNS indirection for a single pod registering with itself), plus setting `CLUSTER_ID` explicitly (the official image silently skips storage formatting without it — also fixed in the local `docker-compose.yml` for consistency) |
| 2026-07-17 | Phase 6 | Fixed | `curl -k` showed a "working" HTTPS endpoint that was actually serving Traefik's own default self-signed cert, not the cert-manager-issued one — Ingress's SNI-based cert matching needs a real hostname, which a bare-IP deployment doesn't have. Only caught by inspecting the served certificate directly (`openssl s_client`), not by `-k`. Fixed with a Traefik `TLSStore` default certificate |
| 2026-07-17 | Phase 7 | Done | Metrics: `spring-boot-starter-actuator` + Micrometer/Prometheus on the backend (plus a hand-added `settlement.outcome` counter, TDD), `prometheus-fastapi-instrumentator` on ml-service. `/actuator/**` kept off the public Ingress via explicit controller path prefixes rather than a `/` catch-all |
| 2026-07-17 | Phase 7 | Done | Logs: JSON structured logging on both services (`logstash-logback-encoder` / a small custom Python formatter), `RequestIdFilter` (TDD) correlating a request across both services' logs, `settlementId`/`invoiceId` in MDC. Loki + Promtail deployed, scoped to just this project's namespaces |
| 2026-07-17 | Phase 7 | Done | Traces: OpenTelemetry Java agent (backend) and Python auto-instrumentation (ml-service), zero code changes, exported to a self-hosted Jaeger. Verified one trace spans `POST /invoices/{id}/finance` through the ml-service `/score` call and back |
| 2026-07-17 | Phase 7 | Done | Alerts: Prometheus + Alertmanager, rules for backend/ml-service downtime and settlement failure-rate spikes. No real paging integration (no on-call for a portfolio project) — verified by scaling ml-service to zero and watching the alert fire, then clear on recovery |
| 2026-07-17 | Phase 7 | Done | Grafana deployed with Prometheus/Loki/Postgres datasources (the fraud-score dashboard queries `fraud_assessments` directly) and hand-written dashboards; full stack manually verified end to end against the live deployment |
| 2026-07-17 | Phase 7 | Fixed | Jaeger 2.x isn't published to Docker Hub yet (`ImagePullBackOff` on `jaegertracing/all-in-one:2.19.0`) — switched to `1.72.0`. Separately, both OTel SDKs needed `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` set explicitly, since the default (`http/protobuf`, port 4318) doesn't match Jaeger's gRPC-only port 4317 |
| 2026-07-17 | Phase 7 | Fixed | Promtail shipped zero logs despite correct RBAC and correct file paths — two separate bugs: log files are root-owned 640 and Promtail doesn't run as root by default (fixed with `runAsUser: 0`), and Promtail's Kubernetes pod discovery silently scopes itself to a `spec.nodeName` field selector built from `HOSTNAME`, which defaults to the pod's own name inside a container, not the actual node name (fixed via the Downward API) |
| 2026-07-17 | Phase 8 | Done | Backend: three new list endpoints (`GET /settlements`, `GET /accounts/{id}/settlements`, `GET /invoices`), TDD, paginated via Spring Data `Page`/`Pageable`, row-level filtered for `READ_ONLY` via a nullable-`:ownerId`-parameter JPQL pattern — see `backend.md`/`security.md` |
| 2026-07-17 | Phase 8 | Done | Frontend: Next.js 16 admin dashboard (`frontend/`) — login, settlements list/detail, account detail with settlement history, invoices list/detail with a finance action, reconciliation mismatches list with resolve/trigger-run actions. Auth via an httpOnly/secure cookie set by a Route Handler proxy, no global client-state library. Full detail in `docs/frontend.md` |
| 2026-07-17 | Phase 8 | Done | Deployment: `frontend/Dockerfile` (multi-stage, Next.js `output: "standalone"`), `infra/k8s/14-frontend.yaml` (Deployment/Service/Ingress on the `/app` path prefix, reusing the backend's TLS secret), `.github/workflows/ci.yml` extended with a `test-frontend` job and frontend build/push/deploy steps in `deploy-staging` |
| 2026-07-17 | Phase 8 | Fixed | Next.js 16 renamed the `middleware.ts` file convention to `proxy.ts` (confirmed via `node_modules/next/dist/docs/` and the official codemod) — renamed before it shipped, not caught after the fact |
| 2026-07-17 | Phase 8 | Fixed | The dashboard's own pages (`/settlements`, `/invoices`, `/reconciliation`, `/accounts/[id]`) would have collided with the backend `Ingress`'s identical path prefixes on the same bare-IP TLS NodePort — caught before deploying by re-reading `infra/k8s/06-backend.yaml`, not after a broken deploy. Fixed with a `/app` `basePath`, which in turn required manually prefixing every client-side `fetch()` call and `proxy.ts`'s `NextResponse.redirect(new URL(...))` targets, since Next only auto-applies `basePath` to `next/link`/`next/router` — confirmed the gap empirically (`curl` against a local `next start`) before relying on it |
| 2026-07-17 | CI | Fixed | `deploy-staging` never actually ran on a push to `dev` — `test-backend` failed on GitHub-hosted runners with `mvnw: ... apache-maven-3.9.9/bin/mvn: not found`. Root cause: `mvnw` was never the real Maven Wrapper; since the first Phase 1 commit it was a 4-line script hardcoding an exec path into this specific machine's local `~/.m2/wrapper/dists` cache (a workaround for "no system-wide `mvn`" that replaced the wrapper instead of just fixing `PATH`), which cannot exist on a fresh CI VM. Regenerated the real, portable, self-downloading Maven Wrapper via `mvn -N wrapper:wrapper -Dmaven=3.9.9`; verified locally (`./mvnw test`, cold cache, 194 tests pass) before pushing |
| 2026-07-17 | Phase 8 | Done | Deployed to staging and manually verified end-to-end through the real frontend code paths (not just direct API calls): logged in as `admin1`, browsed settlements/invoices/accounts/reconciliation with live data, financed a test invoice through the actual `FinanceButton` → Route Handler → backend flow (correct 80% advance rate, 2% fee, disbursement settlement, and UI updated to reflect `FINANCED`) |
| 2026-07-17 | Phase 9 | Fixed | Before ever running a load test: designing the chaos tests surfaced that `SettlementService.createSettlement` had no exception handling at all around the external gateway call — a real network timeout would leave the settlement `PENDING` and its idempotency key `IN_PROGRESS` forever, 409-ing every retry permanently. Fixed (TDD) to resolve to `UNKNOWN`, the state machine's existing "we don't know for certain" state |
| 2026-07-17 | Phase 9 | Done | Chaos tests built: `SettlementChaosIntegrationTest` (real Postgres + real Kafka via Testcontainers) proves network failure resolves to `UNKNOWN` not stuck-forever, concurrent retries against a slow gateway produce exactly one settlement, and duplicate/redelivered Kafka messages leave the read model unchanged on replay. See `docs/testing.md` |
| 2026-07-17 | Phase 9 | Done | Load testing built: k6 script (`load/settlement-load-test.js`) exercising `POST /settlements` and invoice financing under concurrent load against the local dev stack, with an explicit duplicate-idempotency-key burst and an account-balance correctness check in `teardown()` (not just per-request status checks) |
| 2026-07-17 | Phase 9 | Fixed | First load test run found a second, related gap: under heavy concurrent contention on a small account pool, `finalizeWithRetry`'s 5-attempt deadlock retry was exhausted for 3 of 1156 settlements, leaving them stuck the same way as the gateway-exception gap above (no data corruption — ledger stayed balanced — but genuinely stuck, not just slow). Fixed (TDD) with a second, independent retry budget that falls back to `UNKNOWN`. Re-ran the load test after the fix: 13 real deadlocks occurred, 9 settlements resolved via the new fallback, zero balance discrepancies, 100% of checks passed |
| 2026-07-17 | Phase 9 | Fixed | Pushing Phase 9's `dev` merge to staging failed twice: `deploy-staging` reported "Failed" both times on the backend `kubectl rollout status --timeout=180s` step. Confirmed by checking the server directly (not just trusting the CI red X) that this was a false negative, not a real problem — the backend pod genuinely took ~6+ minutes to become ready under real CPU contention on the shared box, and the rollout completed successfully on its own both times regardless of the CI step giving up early. Raised the timeout to 360s |
| 2026-07-18 | Frontend | Done | Whole dashboard restyled neo-brutalist (bold black borders, flat accent colors, hard offset shadows, sharp corners — design tokens in `frontend/app/globals.css`, see `docs/frontend.md`). Added a public `/about` "Build Story" page (no login required) presenting a curated, phase-by-phase summary of this project's real challenges and fixes, sourced from this status log and every `docs/*.md` decisions log — not new research, just a readable showcase for anyone viewing the live demo without credentials |

## Rules for working on this project

- TDD is not optional. Write the failing test before the implementation for every new piece of business logic.
- Every component doc in `docs/` must be updated in the same session as the code change it describes. Do not batch doc updates for later.
- No secrets, API keys, or credentials in any file. Use environment variables and reference them by name only.
- Every new Kafka topic gets an entry in `kafka-events.md` before the first producer is written.
- Every new endpoint gets an entry in `backend.md` and a security note in `security.md` if it touches account data.
- No settlement logic gets written without an idempotency key check and an explicit state transition. See `reconciliation.md` before touching any money-movement code.
- See `docs/DOCS_MAINTENANCE.md` for the full doc update process.
