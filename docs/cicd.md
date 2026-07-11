# CI/CD

## Purpose

Describes the build, test, and deployment pipeline.

## Current state

Not yet built. Planned for Phase 5:

- GitHub Actions runs the full test suite on every push
- Docker images built for backend, ML service, and frontend
- Images pushed to a container registry, then deployed to Kubernetes (staging namespace) via `kubectl apply` or Helm
- Staging environment deploys automatically on merge to `main`
- Production deploys require a manual approval step
- Prometheus and Grafana, set up in Phase 6, provide health and performance visibility post-deploy

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Manual approval for production deploys | Standard safeguard for a financial system, even in a portfolio project |

## Open questions

- None yet.
