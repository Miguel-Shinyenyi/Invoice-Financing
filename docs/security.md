# Security

## Purpose

Describes authentication, authorization, and access control rules across the platform.

## Current state

Phase 2 is built. Every endpoint except `/auth/**` and the OpenAPI/Swagger paths requires a valid JWT access token.

- **Authentication**: `POST /auth/login` exchanges a username + password for a short-lived JWT access token (15 min default, `settlement-engine.jwt.access-token-ttl-seconds`) and an opaque refresh token (30 days default, `settlement-engine.refresh-token.ttl-days`). `JwtAuthenticationFilter` parses the `Authorization: Bearer` header on every request and populates the Spring Security context; on a missing/invalid/expired token the request proceeds unauthenticated and is rejected downstream with a `401` (uniform JSON body via a custom `AuthenticationEntryPoint`, not Spring's default).
- **Refresh**: `POST /auth/refresh` rotates the refresh token on every use — the old one is revoked and a new one issued together with a new access token. Reusing an already-rotated or revoked refresh token fails with `401`. See `reconciliation.md`-style reasoning in `backend.md`'s decisions log for why rotation (not a static long-lived token) was chosen.
- **Logout**: `POST /auth/logout` revokes a refresh token. Idempotent — revoking an already-invalid token is not an error.
- **Roles**: `ADMIN`, `SUPPORT`, `READ_ONLY`, stored on `users.role`. `POST /settlements` requires `ADMIN` or `SUPPORT` (`@PreAuthorize`); `GET` endpoints are open to any authenticated role, subject to row-level restriction below. A role check failure returns `403` with a uniform JSON body via a custom `AccessDeniedHandler`.
- **Row-level permissions**: `users.owner_id` links a `READ_ONLY` user to the `ledger_accounts.owner_id` they're allowed to see. `RowLevelAccessGuard.requireOwnership` is a no-op for `ADMIN`/`SUPPORT`; for `READ_ONLY` it throws `AccessDeniedException` (403) unless the user's `ownerId` matches the resource's owner (for settlements, either the source or destination account's owner). `ADMIN`/`SUPPORT` users have no `owner_id` and are never row-restricted.
- **Audit logging**: `AuditLogService` writes to `audit_log` for login attempts (`SUCCESS`/`FAILURE`) and for `GET /accounts/{id}`, `GET /settlements/{id}`, `POST /settlements` (`SUCCESS`/`DENIED` for the row-level check). **Known gap**: a role-check denial from `@PreAuthorize` (e.g. a `READ_ONLY` user attempting `POST /settlements`) happens in the AOP interceptor before the controller method body runs, so it is *not* currently audit-logged — only row-level denials inside a method body are. See Open Questions.
- **Password storage**: BCrypt (`org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder`), industry standard for password hashing (adaptive cost, salted).
- **JWT secret**: read from `JWT_SECRET` env var, with a dev-only placeholder default in `application.yml` (same pattern as the DB password default) — must be overridden for anything beyond local dev.
- **No user registration endpoint yet** — users are seeded directly into the `users` table for now. Not in Phase 2 scope; revisit if/when self-service signup is needed.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Short-lived JWT access token (15 min) + rotating opaque refresh token, stored hashed | Resolves the open question below. Matches OAuth2/OWASP-recommended refresh token rotation: short access-token lifetime limits exposure if leaked, and rotation gives real server-side revocation without needing an access-token blocklist (which would undermine JWT's statelessness) |
| 2026-07-11 | `users.owner_id` nullable, links a `READ_ONLY` user to `ledger_accounts.owner_id` for row-level restriction | Directly matches this doc's own requirement ("a user only sees accounts they own or are permitted to view"); `ADMIN`/`SUPPORT` have no owner and are unrestricted |
| 2026-07-11 | Refresh tokens stored as a SHA-256 hash, never the raw value, same pattern as `idempotency_keys.request_hash` | A stolen database dump must not hand out usable refresh tokens |
| 2026-07-11 | `UserDetailsServiceAutoConfiguration` explicitly excluded | Spring Boot auto-creates an in-memory user with a random generated password when no `UserDetailsService` bean exists; since authentication is entirely JWT-based and never goes through Spring Security's `AuthenticationManager`, that default user is unused noise (and mildly confusing to find in logs) |
| 2026-07-11 | Phase 1 endpoints shipped without authentication (superseded) | Was the deliberate Phase 1 gap; closed now that Phase 2 is built |
| 2026-07-11 | JWT over session-based auth | Stateless, works cleanly with a separate frontend and multiple backend services |

## Open questions

- `@PreAuthorize` role-check denials aren't audit-logged (see Current state). Would need a custom `AccessDeniedHandler` enrichment or a method-security-specific hook to capture the attempted action/actor before the exception reaches the filter chain. Revisit if audit completeness for this case becomes a real requirement.
- No token revocation-on-password-change or "log out all devices" mechanism yet — would need to revoke all of a user's `refresh_tokens` rows, not currently exposed.
