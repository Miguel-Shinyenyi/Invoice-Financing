# Storage

## Purpose

Describes how files are stored in object storage: bucket structure, access patterns, and the audit trail. Uses MinIO (S3-API-compatible), not AWS S3 — see `docs/server-setup.md` for why.

## Current state

**Infrastructure only** (Phase 6): MinIO runs in-cluster (`infra/k8s/04-minio.yaml`) with a persistent volume and root credentials in a Kubernetes Secret. Nothing in the backend writes to it yet — the actual application-level feature (receipts, statements) is still not built. Planned shape, unchanged from the original design, once it is:

- One bucket for transaction receipts, one for exported statements
- Presigned URLs for downloads, expiring after 15 minutes (MinIO supports the same presigned-URL API as S3, so this doesn't change)
- Every balance-changing action writes a row to `audit_log` with actor, action, target, and timestamp (already built — see `security.md`)

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Presigned URLs instead of proxying downloads through the backend | Reduces backend load, standard pattern for secure object-storage access |
| 2026-07-17 | MinIO instead of AWS S3 | No AWS account exists for this project; MinIO is API-compatible so the presigned-URL design above carries over unchanged whenever the application-level feature gets built |

## Open questions

- Retention policy for old receipts. Decide once storage costs become relevant.
- When the actual receipt/statement-writing feature gets built — not scoped to any current phase.
