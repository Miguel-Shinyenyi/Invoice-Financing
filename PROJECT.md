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

Current phase: **Phase 1, done. Phase 2 (security and access control) not yet started.**

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

## Rules for working on this project

- TDD is not optional. Write the failing test before the implementation for every new piece of business logic.
- Every component doc in `docs/` must be updated in the same session as the code change it describes. Do not batch doc updates for later.
- No secrets, API keys, or credentials in any file. Use environment variables and reference them by name only.
- Every new Kafka topic gets an entry in `kafka-events.md` before the first producer is written.
- Every new endpoint gets an entry in `backend.md` and a security note in `security.md` if it touches account data.
- No settlement logic gets written without an idempotency key check and an explicit state transition. See `reconciliation.md` before touching any money-movement code.
- See `docs/DOCS_MAINTENANCE.md` for the full doc update process.
