# Server Setup

## Purpose

Step-by-step setup for the server this project deploys to, and the decisions specific to running on a **shared** bare-metal box rather than a dedicated cloud account. Supersedes the original AWS-based plan (this file was `aws-setup.md`) — see the decisions log for why.

## Current state

Built (Phase 6). Runs on a bare-metal Ubuntu VM from ssdnodes (`107.155.122.29`), reachable as user `miguel` with passwordless sudo. This box is **shared with other tenants** (other Linux accounts, an already-running Docker Swarm, and a handful of unrelated containers bound to public ports) — every step below is written to avoid touching anything outside this project's own footprint.

### 1. Access and privileges

- SSH key-based auth only, user `miguel`. Passwordless sudo granted via `/etc/sudoers.d/miguel-nopasswd` (`miguel ALL=(ALL) NOPASSWD:ALL`) — set up once, interactively, by the account owner (not over a script), since it requires authenticating with the account password.
- Private keys are never pasted into chat/tooling long-term: any key that passes through an AI tool's conversation transcript is treated as burned and rotated once the immediate task is done.

### 2. Baseline hardening

- `apt update && apt upgrade`, run once. If it pulls a kernel update requiring a reboot, the reboot is **not** performed automatically — this box has other tenants' containers running, so a reboot is coordinated separately, not triggered as a side effect of routine patching.
- UFW is pre-existing on this box (owned by whoever administers it, not this project) with a large existing allow-list for other tenants' services. This project does not touch existing rules or change default policy — it only ever adds narrowly-scoped rules for its own ports, added at the point they're actually needed (not speculatively).
- Deploy root: `/opt/invoice-financing`, owned by `miguel`.

### 3. k3s (single-node Kubernetes)

Installed via `curl -sfL https://get.k3s.io | sudo INSTALL_K3S_EXEC='--disable=servicelb' sh -`.

Two deviations from a default k3s install, both because this is a shared box:

- **`--disable=servicelb`**: k3s's default Klipper ServiceLB binds `LoadBalancer`-type services directly to host ports. This box already has other containers bound to `0.0.0.0:80` and `0.0.0.0:443` (an existing reverse proxy for other tenants) — letting k3s fight over those ports was not an option. With servicelb disabled, `LoadBalancer` services just stay `<pending>` for an external IP instead of attempting a host bind.
- **Traefik (k3s's bundled ingress) pinned to fixed NodePorts 30080/30443** instead of the random high ports Kubernetes would otherwise assign, via `kubectl patch svc traefik -n kube-system --type=json ...` setting `spec.type: NodePort` and both `nodePort` values explicitly. (A `HelmChartConfig` was tried first, as the "supported" way to persist this across chart upgrades — the values didn't take effect against this chart version's schema and wasn't worth further troubleshooting for a single-node deployment; the direct patch works but **will need to be reapplied if the Traefik Helm release is ever upgraded/reconciled**, since it isn't stored in the chart's own values. Documented here rather than silently left as a trap.)

Kubeconfig: **not** written world-readable (`--write-kubeconfig-mode` was deliberately *not* set — the default k3s config at `/etc/rancher/k3s/k3s.yaml` is root-only). `miguel`'s own copy lives at `~/.kube/config` (mode `600`, owned by `miguel`), created via `sudo cat /etc/rancher/k3s/k3s.yaml > ~/.kube/config`. `KUBECONFIG=$HOME/.kube/config` is exported in `~/.bashrc` since the k3s-bundled `kubectl` binary defaults to `/etc/rancher/k3s/k3s.yaml` otherwise (a k3s-specific convention, not standard `kubectl` behavior).

The cluster's API server (port 6443) is **not** exposed externally — not in any UFW allow rule, so it stays default-denied. All `kubectl` usage is from an SSH session on the box itself, not a remote kubeconfig. This is a deliberate, narrower choice than a typical dedicated-cluster setup: it trades remote convenience for a smaller attack surface on a box other people also use.

### 4. Local container registry

`registry:2` (the official Docker registry image) run as a plain Docker container, not a k3s workload: `sudo docker run -d --name invoice-financing-registry --restart unless-stopped -p 127.0.0.1:15000:5000 registry:2`.

- Port **15000**, not the registry's conventional 5000 — another tenant's container already had `0.0.0.0:5000` bound.
- Bound to `127.0.0.1` only, never `0.0.0.0` — only `docker push`/`docker build` on this host and k3s's own containerd (same host) need to reach it; there's no reason for it to be internet-reachable at all.
- k3s's containerd trusts it as a plain-HTTP ("insecure") registry via `/etc/rancher/k3s/registries.yaml` (`mirrors["localhost:15000"]` + `configs["localhost:15000"].tls.insecure_skip_verify: true`), then `sudo systemctl restart k3s` to pick it up. Verified end-to-end: pushed a `busybox` test image to `localhost:15000/test:latest`, ran a throwaway pod referencing that image, confirmed `kubectl describe pod` shows a clean pull.

### 5. cert-manager and TLS

cert-manager v1.21.0 installed via the official manifest. Since no domain points at this box, Let's Encrypt isn't usable (it needs a resolvable hostname) — set up a proper two-step self-signed CA instead of one throwaway cert, so it's a clean swap to Let's Encrypt later rather than a re-architecture (`infra/k8s/01-cert-manager-issuer.yaml`):

1. `ClusterIssuer/selfsigned-bootstrap` (kind `SelfSigned`) — exists only to bootstrap step 2
2. `Certificate/invoice-financing-ca` (namespace `cert-manager`, `isCA: true`, ECDSA P-256, 10-year duration) issued by the bootstrap issuer — this is the project's root CA
3. `ClusterIssuer/invoice-financing-ca-issuer` (kind `CA`, signs with the root CA's key) — every real leaf certificate (e.g. the backend's ingress TLS cert) gets issued by this one

Swapping to Let's Encrypt later means adding an `ACME`-type `ClusterIssuer` and changing the `issuerRef` on the leaf `Certificate` resources — the rest of this structure stays as-is.

### 6. GitHub Actions self-hosted runner

Installed under `~/actions-runner` as user `miguel`, registered against the repo with a short-lived registration token generated from the GitHub UI (Settings → Actions → Runners → New self-hosted runner — the token is only used once, at registration, and expires in about an hour regardless), then installed as a systemd service (`sudo ./svc.sh install miguel && sudo ./svc.sh start`) so it survives reboots and reconnects automatically.

The runner polls GitHub outbound — no inbound port is needed for CI to reach this box, which matters on a shared server where opening new inbound ports at all is something to avoid.

The runner runs as `miguel`, who isn't in the `docker` group (deliberately — see the k3s kubeconfig note above about not handing out standing root-equivalent access unnecessarily). CI's `deploy` job (`.github/workflows/ci.yml`) uses `sudo docker` for image builds/pushes rather than adding `miguel` to the `docker` group, since `miguel` already has passwordless sudo — this keeps the elevation scoped to the one workflow step that needs it rather than a blanket group membership.

### 7. Deploy runbook

Manual first deploy (`.github/workflows/ci.yml`'s `deploy` job automates this on every push to `dev` afterward):

1. Build and push both images from the server (`sudo docker build ... && sudo docker push localhost:15000/...`).
2. Create the three required Secrets directly on the server, values generated there via `openssl rand`, piped straight into `kubectl create secret generic ... --dry-run=client -o yaml | kubectl apply -f -` (never written to a file, never leaves the server):
   - `postgres-credentials`: `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`
   - `backend-secrets`: `JWT_SECRET`
   - `minio-credentials`: `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`
3. `kubectl apply -f infra/k8s/` (order matters for a from-scratch deploy: namespace and cert-manager issuer first, then the rest — the numeric filename prefixes enforce this if applied as a directory glob).
4. Seed the platform ledger account: once Postgres/Flyway have run (triggered by the backend's own startup), insert a `ledger_accounts` row with the same id as `infra/k8s/07-backend-config.yaml`'s `INVOICE_PLATFORM_ACCOUNT_ID`. Also seed an admin user the same way local dev does (see `PROJECT.md`'s "Running the application").
5. Verify: `kubectl get pods -n invoice-financing` all `Running`; pull the root CA (`kubectl get secret invoice-financing-ca-secret -n cert-manager -o jsonpath='{.data.tls\.crt}' | base64 -d`) and `curl --cacert` the deployed backend rather than `-k`, to actually prove the TLS chain is correct, not just that *some* cert was presented.

Two bugs only surfaced at this step, not in any earlier checkpoint or in local dev:

- **Kafka crash-looped on first boot** ("channel manager timed out before sending the request" during controller self-registration) — fixed by routing `KAFKA_CONTROLLER_QUORUM_VOTERS` through `localhost` instead of the Kubernetes Service DNS name, and separately by setting `CLUSTER_ID` explicitly (the official image silently skips storage formatting without it). Full explanation in `docs/kubernetes.md`'s decisions log.
- **`curl -k` showed a working HTTPS endpoint even though the wrong certificate was being served** — Traefik was silently falling back to its own `TRAEFIK DEFAULT CERT` instead of the cert-manager-issued one, because there's no hostname for Ingress's SNI-based cert matching to key off. Only caught by checking the actual served certificate's subject/issuer with `openssl s_client`, not by `curl -k` (which doesn't care what cert it gets, only that the connection completes). Fixed with a Traefik `TLSStore` default certificate. **Lesson**: `-k`/`--insecure` proves a TLS connection completes, not that the *right* certificate was served — verifying against the actual CA (or at minimum inspecting the served cert directly) is the only way to catch this class of misconfiguration.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-17 | Self-hosted bare-metal server (ssdnodes) instead of AWS, replacing the entire original `aws-setup.md`/EKS/S3 plan | User's own infrastructure choice; no AWS account was ever created for this project. `docs/kubernetes.md`, `docs/cicd.md`, `docs/storage.md` updated in step accordingly |
| 2026-07-17 | k3s installed with `--disable=servicelb`, Traefik pinned to NodePort 30080/30443 instead of the default LoadBalancer-on-host-80/443 behavior | Discovered mid-setup that this box already has other tenants' containers bound to host 80/443/5000/etc. — k3s must not fight over ports it doesn't own |
| 2026-07-17 | k3s API server (6443) not exposed externally; kubeconfig kept root/owner-only, not world-readable | Shared box — minimizing both network attack surface and local-privilege-escalation surface (a world-readable kubeconfig would hand cluster-admin to any other account on the machine) |
| 2026-07-17 | No automatic reboot after `apt upgrade`, even when a kernel update requests one | Other tenants have running containers; a reboot is a shared-impact action that needs explicit coordination, not something routine patching should trigger silently |

## Open questions

- The Traefik NodePort pin is a direct `kubectl patch`, not a persisted Helm value — needs reapplying if the `traefik` Helm release is ever upgraded. Revisit the correct `HelmChartConfig` values schema for this chart version if that becomes annoying.
- No real domain points at this box yet; TLS is self-signed for now (see `docs/kubernetes.md`). Revisit once/if a subdomain is available.
