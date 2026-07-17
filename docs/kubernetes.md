# Kubernetes

## Purpose

Describes how the platform's services get deployed and scaled on Kubernetes.

## Current state

Built (Phase 6). A single-node k3s cluster on a bare-metal server (see `docs/server-setup.md`) — not AWS/EKS, see the decisions log for why. Everything runs in one `invoice-financing` namespace:

- `postgres`, `kafka`, `minio`: each a single-replica `Deployment` (strategy `Recreate`, since each mounts a `ReadWriteOnce` PVC on the `local-path` storage class k3s ships with) + a `PersistentVolumeClaim` + a `ClusterIP` Service. None are exposed outside the cluster.
- `fraud-detection-ml`: single-replica `Deployment` + `ClusterIP` Service, internal only — called by the backend, never reached from outside.
- `backend`: single-replica `Deployment` + `ClusterIP` Service + `Ingress` (Traefik, k3s's bundled ingress controller) + a leaf TLS `Certificate` (see below). This is the only component reachable from outside the cluster.
- Config/secrets: a `ConfigMap` (`backend-config`) for the one non-sensitive value (`INVOICE_PLATFORM_ACCOUNT_ID`, just a `ledger_accounts.id`), and three `Secret`s (`postgres-credentials`, `backend-secrets`, `minio-credentials`) created directly on the server with randomly-generated values — never committed to git, never round-tripped through any chat/tooling transcript.
- All manifests live in `infra/k8s/`, applied in filename order (`00-namespace.yaml` through `07-backend-config.yaml`).

Resource requests/limits are set on every Deployment (the box has 8 vCPU / 31GB RAM, but requests/limits are good practice regardless, and matter more here since the box is shared with other tenants — see `docs/server-setup.md`).

### TLS

No domain points at this server, so Let's Encrypt isn't usable. cert-manager issues from a self-signed root CA instead (`infra/k8s/01-cert-manager-issuer.yaml`, described fully in `docs/server-setup.md`), and the backend's `Ingress` references a leaf `Certificate` (`infra/k8s/06-backend.yaml`) with `ipAddresses: ["107.155.122.29"]` as its only SAN (no DNS name to put there).

Because there's no hostname, the `Ingress` has no `rules[].host` and its `tls[]` entry has no `hosts` list — both would only make sense for real domains. Without a host to SNI-match against, Traefik doesn't know to associate the ingress's cert with incoming connections and falls back to serving its own built-in self-signed `TRAEFIK DEFAULT CERT` instead (found by testing with `openssl s_client` — `curl -k` alone didn't reveal this, since it skips verification entirely). Fixed with a Traefik `TLSStore` named `default` (`traefik.io/v1alpha1`) pointing `defaultCertificate` at the cert-manager-issued secret, which makes it the fallback certificate for every TLS connection Traefik terminates, independent of SNI.

### Networking around a shared host

This server already runs other tenants' workloads (see `docs/server-setup.md`), which shaped several choices here:

- k3s installed with `--disable=servicelb`, so `LoadBalancer`-type Services (including Traefik's default one) don't try to bind host ports 80/443 that other tenants already occupy.
- Traefik's Service is patched to `NodePort` with fixed ports **30080/30443** instead of the default random high ports.
- The local image registry (`localhost:15000`, not the conventional 5000 — already taken) is bound to `127.0.0.1` only.
- No public DNS/domain, hence the self-signed-CA TLS approach above.

### Not built

- Horizontal Pod Autoscaler: no traffic pattern yet to autoscale against on a single-node cluster; revisit if this ever needs more than one node.
- Frontend Deployment/Service: Phase 8, not built yet.
- Helm charts / Kustomize overlays: plain manifests are enough for one environment; revisit if a second environment (e.g. a real staging split) is ever needed.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-17 | Self-hosted k3s on bare metal, not AWS EKS | User's own infrastructure choice; no AWS account was ever created for this project. Supersedes every AWS-specific decision below |
| 2026-07-17 | Single namespace, no staging/production split | The original plan's namespace-per-environment split doesn't add real isolation value on one single-node cluster; deferred until there's an actual second environment to separate from (see `docs/cicd.md`'s matching decision) |
| 2026-07-17 | Postgres, Kafka, and MinIO all run in-cluster, not as managed services | No managed-service equivalent exists on this bare-metal box; this also matches how the local `docker-compose` dev environment already works, just deployed instead of local |
| 2026-07-17 | `--disable=servicelb` at k3s install, Traefik pinned to NodePort 30080/30443 via `kubectl patch` | The box already had other tenants' containers bound to host 80/443; k3s must not fight over ports it doesn't own. The patch isn't persisted via Helm values (tried, didn't take against this chart version) so it needs reapplying if the Traefik release is ever upgraded — see `docs/server-setup.md`'s open questions |
| 2026-07-17 | Self-signed CA (two-step: bootstrap `SelfSigned` issuer -> root `Certificate` -> `CA`-type issuer for leaf certs), not a single throwaway self-signed cert | No domain exists yet for Let's Encrypt; this structure makes switching to an `ACME` issuer later a one-line `issuerRef` change on the leaf `Certificate`, not a re-architecture |
| 2026-07-17 | Traefik `TLSStore` with a default certificate, instead of relying on Ingress `tls.hosts` | Ingress host-based SNI matching only works for real hostnames; without one, Traefik silently falls back to its own self-signed cert. A default `TLSStore` cert applies regardless of SNI, which is what a bare-IP deployment actually needs |
| 2026-07-17 | Kafka's `KAFKA_CONTROLLER_QUORUM_VOTERS` set to `1@localhost:9093`, not the Kubernetes Service DNS name | Found via crash-looping on first deploy ("channel manager timed out before sending the request" during controller registration): routing a single pod's self-registration with itself through the Service (ClusterIP -> kube-proxy iptables -> CoreDNS) added indirection a same-pod handshake doesn't need, and was unreliable enough on this shared/contended host to intermittently miss Kafka's internal registration timeout. `localhost` removed the indirection entirely and has been stable since |
| 2026-07-17 | `CLUSTER_ID` set explicitly on the Kafka container (both here and in `infra/docker-compose.yml`) | The official `apache/kafka` image silently skips storage formatting without it, failing later with a cryptic "No readable meta.properties files found." Local dev never hit this because that container has no declared volume and tends to get reused rather than freshly recreated |

## Open questions

- Whether to use Helm or plain Kustomize for environment config, if a second environment is ever added.
