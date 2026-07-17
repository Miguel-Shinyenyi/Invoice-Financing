# CI/CD

## Purpose

Describes the build, test, and deployment pipeline.

## Current state

Built (Phase 6). `.github/workflows/ci.yml`, three jobs:

- `test-backend` (GitHub-hosted `ubuntu-latest`): `./mvnw test` — full Java suite, including the Testcontainers integration tests (GitHub-hosted runners ship with Docker preinstalled).
- `test-ml-service` (GitHub-hosted `ubuntu-latest`): installs `ml-service/requirements-dev.txt`, runs the pytest suite.
- `deploy` (self-hosted, runs **on the ssdnodes box itself**, `needs: [test-backend, test-ml-service]`, only on `push` to `dev`): builds and pushes both Docker images (tagged with the git SHA and `latest`) to the local registry (`localhost:15000`), then `kubectl apply -f infra/k8s/` followed by `kubectl set image` on the backend and ml-service Deployments and `kubectl rollout status` to confirm the rollout actually succeeded.

The self-hosted runner (`docs/server-setup.md`) polls GitHub outbound and is installed as a systemd service, so no inbound port is needed for CI to reach the deploy target and it survives reboots/reconnects automatically. It runs as `miguel`, who isn't in the `docker` group — the `deploy` job uses `sudo docker` for image builds rather than a standing group membership.

### Not built

- A real staging/production split — see the decisions log. Everything currently deploys to the one `invoice-financing` namespace on push to `dev`.
- A manual-approval gate before deploying — deferred along with the staging/production split, since there's only one environment to gate right now.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-17 | Self-hosted GitHub Actions runner on the deploy target itself, not a GitHub-hosted runner SSHing in | The runner polls GitHub outbound, so no inbound port needs to be opened on a server shared with other tenants. Simplest to secure |
| 2026-07-17 | Deploy on push to `dev` only; no `main` deploy logic yet | `main` has never had anything merged into it in this project's history (per its established git-flow: `main` <- `dev` <- `feature/*`) — inventing "production" semantics for a branch that isn't actually used yet would be speculative. Revisit once `main` starts meaning something |
| 2026-07-17 | No staging/production namespace split, superseding the original plan in `docs/kubernetes.md` | A namespace split on one single-node cluster doesn't provide real isolation — both "environments" would share the same node, same Postgres/Kafka capacity, same everything. Real separation needs a second environment to actually separate from; revisit if one exists (e.g. a second server, or a managed cluster) |
| 2026-07-17 | No manual-approval gate before deploy, superseding the original "manual approval for production deploys" decision | That decision assumed a real production environment would exist by Phase 6; it doesn't yet (see above). An approval gate with nothing meaningfully at stake beyond "the only environment" is friction without the safety benefit it was meant to provide. Revisit once there's an actual production environment to protect |
| 2026-07-17 | `deploy` job uses `sudo docker` rather than adding the runner's user to the `docker` group | The runner's user already has passwordless sudo; a standing `docker` group membership would be an unnecessary permanent privilege grant on a shared box for no added capability |

## Open questions

- Revisit the staging/production split and a manual approval gate once there's a second real environment (a second server, or this cluster growing past single-node).
