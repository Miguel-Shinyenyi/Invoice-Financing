# Storage

## Purpose

Describes how files are stored in S3: bucket structure, access patterns, and the audit trail.

## Current state

Not yet built. Planned for Phase 4:

- One S3 bucket for transaction receipts, one for exported statements
- Presigned URLs for downloads, expiring after 15 minutes
- Every balance-changing action writes a row to `audit_log` with actor, action, target, and timestamp

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Presigned URLs instead of proxying downloads through the backend | Reduces backend load, standard pattern for secure S3 access |

## Open questions

- Retention policy for old receipts. Decide once storage costs become relevant.
