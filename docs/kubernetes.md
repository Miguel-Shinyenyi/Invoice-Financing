# Kubernetes

## Purpose

Describes how the platform's services get deployed and scaled on Kubernetes.

## Current state

Not yet built. Planned for Phase 5, after Docker images exist for each service.

- One cluster, namespaces split by environment: `staging`, `production`
- Each service (backend, ml-service, frontend) gets its own Deployment and Service manifest
- Postgres and Redis run as managed AWS services, not in-cluster, to avoid managing stateful sets for a portfolio project
- Kafka runs as a managed service (MSK) or a lightweight in-cluster deployment for local development
- Horizontal Pod Autoscaler on the backend and ml-service, scaling on CPU and request latency
- Ingress controller routes external traffic to the frontend and backend services
- Secrets managed through Kubernetes Secrets, sourced from AWS Secrets Manager, never committed to git

## Local development

- Use a local cluster (kind or minikube) for development and testing manifests before deploying to AWS (EKS)
- Helm charts for each service once the raw manifests are stable, to manage environment-specific config cleanly

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Postgres and Redis as managed AWS services, not in-cluster | Running stateful databases in Kubernetes adds operational complexity that isn't the point of this project, managed services are standard practice for this scale |
| 2026-07-11 | EKS for production, kind for local dev | Matches real-world workflow: develop against a local cluster, deploy to a managed control plane |

## Open questions

- Whether to use Helm or plain Kustomize for environment config. Decide once the first manifests are written.
