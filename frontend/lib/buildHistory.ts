// Curated from PROJECT.md's status log and every docs/*.md decisions log -- not a separate
// research effort, just a readable summary of the real engineering record for anyone viewing
// this dashboard without repo access. Keep in sync when a new "Fixed" entry lands there.

export interface Challenge {
  problem: string;
  fix: string;
}

export interface BuildPhase {
  phase: string;
  title: string;
  summary: string;
  challenges: Challenge[];
}

export const buildHistory: BuildPhase[] = [
  {
    phase: "Phase 1",
    title: "Core ledger & idempotency",
    summary:
      "The foundation: an idempotent settlement engine with a double-entry ledger and a PENDING/CONFIRMED/FAILED/UNKNOWN state machine, proven under real concurrent load against Postgres (Testcontainers), not just mocked repositories.",
    challenges: [
      {
        problem:
          "Two requests racing on the same idempotency key can fail two different ways in Postgres -- a clean unique-constraint violation, or an outright deadlock -- depending on timing.",
        fix: "Broadened the race-fallback from catching only DataIntegrityViolationException to the wider DataAccessException, and always resolve by re-reading whichever request actually won.",
      },
      {
        problem:
          "Even after that fix, the winning thread's own final commit could deadlock against losing transactions still holding a lock from their own doomed insert attempts.",
        fix: "A bounded, isolated retry (5 attempts) scoped to just that last step, not the whole request.",
      },
    ],
  },
  {
    phase: "Phase 2",
    title: "Auth & row-level access control",
    summary:
      "JWT authentication with rotating refresh tokens, role-based access (ADMIN/SUPPORT/READ_ONLY), and row-level filtering so a READ_ONLY user only ever sees accounts and settlements they own.",
    challenges: [],
  },
  {
    phase: "Phase 3",
    title: "Event-driven read model",
    summary:
      "A transactional outbox publishes settlement events to Kafka; a CQRS read model consumes them into a fast, queryable projection separate from the write-side ledger.",
    challenges: [
      {
        problem:
          "Kafka only guarantees ordering within a single topic-partition, never across topics -- a settlement's CONFIRMED event was observed arriving before its own REQUESTED event.",
        fix: "Every consumer upserts with a last-write-wins check against the event's own timestamp, so a late-arriving REQUESTED can never downgrade an already-CONFIRMED row.",
      },
    ],
  },
  {
    phase: "Phase 4",
    title: "Reconciliation engine",
    summary:
      "A scheduled and event-triggered reconciliation process independently checks settlements against an external source of truth, flagging anything that never resolved cleanly for manual review -- never silently auto-resolving a financial discrepancy.",
    challenges: [],
  },
  {
    phase: "Phase 5",
    title: "Invoice financing",
    summary:
      "The application layer on top of the core engine: submit an invoice, finance it for an advance, detect and collect repayment automatically -- all as settlements through the exact same engine as everything else, no special-cased money movement.",
    challenges: [
      {
        problem:
          "Two concurrent finance requests for the same invoice using different idempotency keys aren't caught by the settlement engine's own idempotency protection, which only guards retries of the same key -- the invoice could have been financed twice.",
        fix: "A pessimistic row lock atomically claims the invoice (ISSUED -> FINANCED) before disbursement is even attempted, in its own short transaction.",
      },
    ],
  },
  {
    phase: "Phase 5.5",
    title: "Fraud detection (ML service)",
    summary:
      "A small Python/FastAPI service scores every financing request before disbursement; the backend fails open (allow, flagged) if the service is unreachable, rather than blocking money movement on an advisory signal.",
    challenges: [
      {
        problem:
          "The JDK HttpClient's default HTTP/2-cleartext-upgrade attempt silently caused the real fraud service to see every request body as missing -- yet the automated unit tests, which stubbed the service with a JDK-based test server, passed cleanly the whole time.",
        fix: "Only caught by manually testing against the real running service, not the automated suite. Fixed by forcing HTTP/1.1 explicitly. A reminder that a protocol-level bug tied to one specific server implementation can hide behind an equally real but differently-behaved test double.",
      },
    ],
  },
  {
    phase: "Phase 6",
    title: "Containerization & deployment",
    summary:
      "Docker images for every service, deployed to a self-hosted k3s cluster on a bare-metal server shared with other tenants -- not AWS, a deliberate infrastructure choice that shaped every networking and TLS decision from here on.",
    challenges: [
      {
        problem: "Kafka (KRaft mode) crash-looped on first boot with a controller self-registration timeout.",
        fix: "Routed the controller's self-registration through localhost instead of the Kubernetes Service DNS name, removing indirection a single pod talking to itself never needed.",
      },
      {
        problem:
          "A bare-IP HTTPS deployment (no domain, so no SNI to match against) was silently serving Traefik's own default self-signed certificate instead of the real one -- and curl -k didn't reveal it, since it skips verification entirely.",
        fix: "Only caught by inspecting the served certificate directly (openssl s_client). Fixed with a Traefik TLSStore default certificate, which applies regardless of SNI.",
      },
    ],
  },
  {
    phase: "Phase 7",
    title: "Observability",
    summary:
      "Prometheus metrics, structured JSON logs correlated by request id, OpenTelemetry traces, and Grafana dashboards -- all self-hosted, so any settlement's full lifecycle is traceable end to end when something goes wrong.",
    challenges: [
      {
        problem: "The intended Jaeger 2.x image wasn't published to Docker Hub yet.",
        fix: "Switched to 1.72.0 and set the OTLP protocol explicitly, since the SDK default (HTTP) didn't match Jaeger's gRPC-only port.",
      },
      {
        problem: "The log-shipping agent (Promtail) shipped zero logs despite correct RBAC and correct file paths.",
        fix: "Two independent bugs stacked: log files were root-owned and the agent didn't run as root by default, and its node discovery silently resolved to the pod's own name instead of the actual Kubernetes node.",
      },
    ],
  },
  {
    phase: "Phase 8",
    title: "This dashboard",
    summary:
      "The Next.js admin UI you're using right now -- settlements, invoices, accounts, reconciliation, all server-rendered, auth via an httpOnly cookie a client script can never read.",
    challenges: [
      {
        problem:
          "This dashboard's own page paths (/settlements, /invoices, ...) would have silently collided with the backend API's identical path prefixes on the one shared origin, since there's no separate hostname to route by.",
        fix: "Caught before it ever broke anything, by re-reading the existing backend Ingress config before deploying. Fixed by serving the whole dashboard under a distinct /app prefix.",
      },
    ],
  },
  {
    phase: "Phase 9",
    title: "Load & chaos testing",
    summary:
      "The project's actual thesis put under real strain: simulated network failures and duplicate delivery at the external call boundary, plus a real k6 load test against the live stack -- proving no double-payment and no lost settlement, not just asserting it.",
    challenges: [
      {
        problem:
          "Before ever running a load test, writing the chaos tests themselves surfaced that a real network timeout talking to the external system had no exception handling at all -- it would have left a settlement stuck forever, unable to resolve on any future retry.",
        fix: "Fixed to resolve to the state machine's existing UNKNOWN state -- \"we don't know for certain, reconciliation will sort it out later\" -- rather than an unhandled crash.",
      },
      {
        problem:
          "The load test itself then found a second, related gap: under real concurrent contention, a bounded deadlock-retry could be exhausted, leaving a small number of settlements stuck the same way.",
        fix: "Added a second, independent retry budget as a last resort. Re-ran the load test afterward: real deadlocks still occurred, every one resolved cleanly, and every account balance still reconciled exactly.",
      },
      {
        problem:
          "Deploys had silently never actually succeeded through the automated pipeline -- a fake Maven wrapper, hardcoded to one machine's local path since this project's very first commit, made every CI test run fail before it could even reach the deploy step.",
        fix: "Regenerated the real, portable, self-downloading wrapper. A reminder not to trust a checked-in tooling script just because it \"works locally.\"",
      },
    ],
  },
];
