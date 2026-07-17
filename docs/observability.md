# Observability

## Purpose

Describes how the platform is monitored: metrics, logs, and distributed traces across services.

## Current state

Built (Phase 7). Self-hosted throughout — no CloudWatch/X-Ray, following the same AWS→self-hosted pivot as Phase 6. All components run in the `invoice-financing` k3s namespace (`infra/k8s/08-*.yaml` through `13-*.yaml`).

### Metrics

- Backend: `spring-boot-starter-actuator` + `micrometer-registry-prometheus`, exposing `/actuator/prometheus`. A hand-added `Counter` (`settlement.outcome`, tagged by `outcome`) in `SettlementTransactions.finalizeSettlement` is the one custom metric — everything else (request latency via `http_server_requests_seconds`, JVM stats) comes free from Actuator's auto-instrumentation.
- ml-service: `prometheus-fastapi-instrumentator`, exposing `/metrics`.
- **`/actuator/prometheus` is never reachable through the public Ingress** — `infra/k8s/06-backend.yaml`'s Ingress uses explicit path prefixes for the real controllers (`/accounts`, `/auth`, `/invoices`, `/reconciliation`, `/settlements`, Swagger) instead of a `/` catch-all, so actuator simply isn't routed there. `SecurityConfig.java` `permitAll()`s `/actuator/**` anyway (network isolation is what actually keeps it private, not auth) since Prometheus reaches it in-cluster via the backend's `ClusterIP` Service.
- Prometheus (`prom/prometheus:v3.13.1`) scrapes both via static targets (no Kubernetes service discovery — three fixed targets isn't worth the RBAC). NodePort 30090.
- Grafana (`grafana/grafana:13.1.0`) provisioned with Prometheus, Loki, and a direct Postgres datasource (the fraud-score-distribution dashboard reads `fraud_assessments` directly — no metric needed for that one). Dashboards: settlement outcome breakdown, backend p95 latency, fraud score distribution, fraud decisions. NodePort 30030, default `admin`/`admin` login (not yet changed — see open questions).

### Logs

- Structured JSON on both services. Backend: `logstash-logback-encoder` (`src/main/resources/logback-spring.xml`). ml-service: a small custom `logging.Formatter` (`ml-service/logging_config.py`) — no new dependency, stdlib `logging` is enough.
- `RequestIdFilter` (backend, `com.settlementengine.core.observability`, built with TDD) and `RequestIdMiddleware` (ml-service) both read an incoming `X-Request-Id` header or generate one, put it in MDC/a contextvar, and echo it back on the response — so a request can be correlated across both services' logs by the same ID, and the backend's own call to the ml-service could in principle be traced by request ID even without full distributed tracing.
- `MDC` also carries `settlementId` (`SettlementService.createSettlement`) and `invoiceId` (`InvoiceService.financeInvoice`) for the duration of those operations.
- Loki (`grafana/loki:3.7.3`, single-binary mode, filesystem storage) + Promtail (`grafana/promtail:3.6.11`, DaemonSet) ship logs from just this project's namespaces (`invoice-financing`, `cert-manager`) — not other tenants' pods on this shared node, via a `relabel_configs` namespace filter (see decisions log for why the RBAC grant itself is still cluster-wide).

### Traces

- OpenTelemetry Java agent (`v2.29.0`, attached via `-javaagent` in the backend `Dockerfile` — zero code changes, auto-instruments Spring MVC, JDBC, Kafka clients, and outbound HTTP) and the OTel Python auto-instrumentation (`opentelemetry-instrument uvicorn ...` in `ml-service/Dockerfile`).
- Jaeger (`jaegertracing/all-in-one:1.72.0`, in-memory storage — traces don't survive a pod restart, acceptable for demo data) receives OTLP over gRPC on port 4317. UI on NodePort 30686.
- Verified end to end: financing an invoice produces one trace spanning `POST /invoices/{id}/finance` through every repository/SQL call, the outbound `POST /score` call to the ml-service, and the resulting `fraud_assessments` insert — one trace ID, both services, in the Jaeger UI.
- Metrics and logs exporters are explicitly disabled on the OTel side (`OTEL_METRICS_EXPORTER=none`, `OTEL_LOGS_EXPORTER=none`) — traces only, since Micrometer/Prometheus and the JSON logging pipeline already own those.

### Alerts

- Prometheus alerting rules (`infra/k8s/08-prometheus.yaml`): `BackendDown`, `MLServiceDown` (both `up == 0` for 1m), `SettlementFailureRateSpike` (`FAILED` outcome rate over total rate > 0.5 for 5m, using the custom counter above).
- Alertmanager (`prom/alertmanager:v0.33.1`) receives them. No real notification integration (Slack/email/PagerDuty) — a portfolio project has no on-call; the `default` receiver has no configured integrations, so firing alerts are visible in Alertmanager's own UI (NodePort 30093) but don't page anyone.
- Verified end to end: scaled `fraud-detection-ml` to zero replicas, watched `MLServiceDown` go `pending` → `firing` in Prometheus and appear as an active alert in Alertmanager, scaled back up, watched it clear.

## Why this matters for this project

External calls (the money-movement side of a settlement) are the most likely failure point, since they depend on systems outside this platform's control, and they're exactly where `UNKNOWN` states get created. Observability here isn't optional, it's how a mismatch or a stuck `UNKNOWN` settlement actually gets noticed instead of sitting silently until reconciliation catches it hours later.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Request ID and transfer ID on every log line | Standard practice for tracing a single business transaction across a distributed system, essential for debugging provider connector failures |
| 2026-07-11 | OpenTelemetry over vendor-specific tracing SDKs | Vendor-neutral, avoids lock-in, works with Jaeger or another OTLP-compatible backend interchangeably |
| 2026-07-17 | Self-hosted Loki and Jaeger instead of CloudWatch Logs and X-Ray | No AWS account exists for this project, matching the Phase 6 pivot |
| 2026-07-17 | `/actuator/**` kept off the public Ingress via explicit controller path prefixes, rather than trying to auth-gate or IP-restrict it in Spring Security | Simpler and more robust: a metrics endpoint that's never routed there at all can't be reached regardless of any auth bug, whereas an auth-based restriction is one misconfiguration away from exposure |
| 2026-07-17 | Static Prometheus scrape targets, not Kubernetes service discovery | Three fixed services on a single-node cluster isn't worth the extra RBAC a `kubernetes_sd_config` needs |
| 2026-07-17 | Promtail scoped to this project's namespaces only, via `relabel_configs`, despite its ClusterRole granting cluster-wide pod/node read | The RBAC grant itself can't be namespace-scoped (Promtail's Kubernetes service discovery needs cluster-wide list/watch by design on a shared node), but the actual log *content* shipped to this project's Loki is filtered to just `invoice-financing`/`cert-manager` — no reason to ingest other tenants' log data even though their metadata is technically visible |
| 2026-07-17 | Jaeger 1.x (`all-in-one:1.72.0`), not 2.x | The `jaegertracing/all-in-one` Docker Hub repo hadn't published 2.x tags yet even though the GitHub source repo had — found via `ImagePullBackOff` on `2.19.0`. 1.x's all-in-one has OTLP enabled by default in recent releases; confirmed explicitly via `COLLECTOR_OTLP_ENABLED=true` rather than relying on that default |
| 2026-07-17 | `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` set explicitly on both services | The OTel SDK defaults to `http/protobuf` (port 4318) when the protocol isn't set, not gRPC (4317) — found via "Failed to export spans" errors from an HTTP client talking to Jaeger's gRPC-only port |
| 2026-07-17 | Promtail's `HOSTNAME` env var overridden via the Downward API (`spec.nodeName`) | Promtail auto-scopes its Kubernetes pod discovery to its own node via a field selector built from `HOSTNAME`, which inside a pod defaults to the pod's own name, not the actual node name — silently producing a selector that matches zero pods. Found by checking Promtail's own `/config` debug page after log ingestion stayed at zero despite correct RBAC and correct file paths |
| 2026-07-17 | Promtail container runs as root (`securityContext.runAsUser: 0`) | Container log files under the hostPath mount are root-owned, mode 640; Promtail's image doesn't run as root by default and silently can't read anything otherwise. The DaemonSet already has host filesystem access via the hostPath mount regardless of container UID, so this isn't a meaningful privilege increase beyond what the mount already grants |

## Open questions

- Grafana's default `admin`/`admin` login hasn't been changed. Low risk right now (NodePort only reachable from wherever the box's firewall allows, not proxied through the public Ingress, and nothing sensitive is stored in Grafana itself beyond datasource configs), but should be rotated before this is ever treated as more than a personal demo.
- Kafka consumer-lag metric/alert: deferred, needs a separate exporter (JMX or protocol-level) disproportionate to the value on a single-node cluster with no real lag scenario to observe.
- Jaeger's in-memory storage means trace history is lost on every pod restart — fine for demo purposes, would need a real backend (Elasticsearch/Cassandra, or Jaeger's simpler badger/file storage) for anything longer-lived.
