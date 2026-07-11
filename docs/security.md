# Security

## Purpose

Describes authentication, authorization, and access control rules across the platform.

## Current state

Not yet built. The Phase 1 endpoints (`POST /settlements`, `GET /settlements/{id}`, `GET /accounts/{id}`) are unauthenticated — anyone who can reach the service can call them. This is a known, deliberate gap, not an oversight: Phase 1 scope was the ledger and idempotency core, and auth is explicitly Phase 2.

Planned for Phase 2:

- JWT-based authentication
- Roles: `admin`, `support`, `read-only`
- Row-level permissions so a user only sees accounts they own or are permitted to view
- All write endpoints require a valid JWT and role check
- All access attempts, successful or not, get logged to `audit_log`

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Phase 1 endpoints ship without authentication | Scoped out of Phase 1 deliberately; documented here so the gap is explicit rather than discovered later. Must close before any non-local deployment. |
| 2026-07-11 | JWT over session-based auth | Stateless, works cleanly with a separate frontend and multiple backend services |

## Open questions

- Token refresh strategy: short-lived access token with refresh token, or long-lived token with revocation list? Decide in Phase 2.
