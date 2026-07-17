# Frontend

## Purpose

Describes the Next.js admin dashboard: pages, components, and how it talks to the backend.

## Current state

Built in Phase 8. Next.js 16.2.10 (App Router), React 19.2.4, TypeScript, Tailwind CSS v4. Lives in `frontend/`.

Pages:

- `/login` — username/password form, posts to `/api/login`
- `/settlements` — paginated list, filterable by status
- `/settlements/[id]` — detail view
- `/accounts/[id]` — balance/owner/currency plus that account's settlement history (paginated)
- `/invoices` — paginated list, filterable by status
- `/invoices/[id]` — detail view, nested advance info, a "Finance this invoice" button (ADMIN/SUPPORT only, shown when status is ISSUED)
- `/reconciliation` — open mismatches list, resolve form per row, "Trigger reconciliation run" button (ADMIN/SUPPORT only; the page itself checks the role and refuses to render for READ_ONLY, backed by the backend's own `@PreAuthorize` on the whole controller)

No global client-state library. Every list/detail page is an `async` Server Component that calls the backend directly (`lib/api.ts`'s `apiFetch`); only the interactive bits (login form, finance button, resolve-mismatch form, trigger-run button, nav sign-out) are small Client Components that call one of a handful of Next.js Route Handlers under `app/api/`, which attach the auth cookie server-side and proxy to the backend.

### Auth

JWT access/refresh tokens are held in httpOnly, secure cookies set by `app/api/login/route.ts` (a Route Handler that calls the backend's `/auth/login`, decodes the access token just to read its `exp` for the cookie's expiry, and sets both cookies) — never exposed to client-side JS, so immune to XSS token theft. `proxy.ts` (Next 16 renamed `middleware.ts` to `proxy.ts` — see decisions log) does a presence-only cookie check to redirect between `/login` and everything else; it does not verify the JWT itself. The backend remains the actual authorization boundary (JWT signature verification, RBAC, row-level ownership checks) for every real data request.

Server Components read the cookie directly (`lib/api.ts`'s `currentUser()`/`requireUser()`) and decode the JWT's claims (`lib/auth.ts`) to drive role-based UI (hiding admin-only buttons, the nav's Reconciliation link) — purely cosmetic, not a security boundary.

### Backend endpoints this dashboard needed that didn't exist before Phase 8

Every prior `GET` was a single-resource lookup by ID. Three list endpoints were added (TDD, same integration-test rigor as the rest of the backend — see `backend.md`):

- `GET /settlements?status=&page=&size=` — backed by the CQRS read model, row-level filtered for READ_ONLY users via a nullable-parameter JPQL query joined against `LedgerAccount`
- `GET /accounts/{id}/settlements?page=&size=` — one account's settlement history, reusing the existing single-account ownership check
- `GET /invoices?status=&page=&size=` — same nullable-parameter pattern, no CQRS read model needed at this scale

`@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` was added to get a stable paginated JSON shape (`{content, page: {size, number, totalElements, totalPages}}`) instead of relying on `PageImpl`'s serialization, which Spring Data itself warns isn't a stable contract.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Next.js over plain React | Matches recent project experience, gives server-side rendering for the dashboard's data-heavy pages |
| 2026-07-17 | No global client-state library | Server Components fetch directly per page; only a handful of small Client Components exist for interactive actions. Revisit if the dashboard's data needs grow past what per-page fetches can handle |
| 2026-07-17 | Auth token in an httpOnly, secure cookie set by a Route Handler proxy, not client-side storage | Keeps the JWT out of reach of any XSS in the dashboard's own code; the alternative (localStorage) was explicitly rejected for that reason |
| 2026-07-17 | App served under the `/app` path prefix (`basePath` in `next.config.ts`), not at `/` | The backend Ingress (`infra/k8s/06-backend.yaml`) already owns `/accounts`, `/auth`, `/invoices`, `/reconciliation`, `/settlements` as explicit path prefixes on the one shared bare-IP TLS NodePort (30443, no hostname to route by instead) — and this dashboard's own pages live at those same paths. `basePath: "/app"` namespaces the whole frontend so both Ingresses coexist on one origin without colliding. Cost: every client-side `fetch()` call and `proxy.ts`'s manually-constructed redirect URLs need the prefix added by hand (`lib/basePath.ts`) since Next only auto-applies `basePath` to `next/link` and `next/router`, not raw `fetch()` or `NextResponse.redirect(new URL(...))` |
| 2026-07-17 | `middleware.ts` renamed to `proxy.ts` | Next.js 16 deprecated the `middleware` file convention in favor of `proxy` (confirmed via `node_modules/next/dist/docs/`, and via the official `middleware-to-proxy` codemod) — pure rename, same presence-only auth-gate logic |
| 2026-07-17 | Frontend's own Ingress reuses the backend's `backend-tls-secret`, no separate `Certificate` | Same bare IP, same TLS NodePort — a second cert for the same IP would be redundant |

## Open questions

None currently open. State management (resolved above): plain Server Component fetches, no client store needed at this scale.
