# Frontend

## Purpose

Describes the Next.js admin dashboard: pages, components, and how it talks to the backend.

## Current state

Built in Phase 8. Next.js 16.2.10 (App Router), React 19.2.4, TypeScript, Tailwind CSS v4. Lives in `frontend/`. To actually try the deployed dashboard (login credentials, a suggested click-through) see `PROJECT.md`'s "Trying it out (staging)" section.

Pages:

- `/login` — username/password form, posts to `/api/login`
- `/settlements` — paginated list, filterable by status
- `/settlements/[id]` — detail view
- `/accounts/[id]` — balance/owner/currency plus that account's settlement history (paginated)
- `/invoices` — paginated list, filterable by status
- `/invoices/[id]` — detail view, nested advance info, a "Finance this invoice" button (ADMIN/SUPPORT only, shown when status is ISSUED)
- `/reconciliation` — open mismatches list, resolve form per row, "Trigger reconciliation run" button (ADMIN/SUPPORT only; the page itself checks the role and refuses to render for READ_ONLY, backed by the backend's own `@PreAuthorize` on the whole controller)
- `/about` — **public, no login required.** A "how this was built" showcase page for anyone viewing the live demo without credentials (a recruiter, e.g.). Renders `lib/buildHistory.ts`, a curated phase-by-phase summary — including the real bugs found and fixed, not just a feature list — sourced from `PROJECT.md`'s status log and every other `docs/*.md`'s decisions log. Not a second source of truth: if a new "Fixed" entry lands in those logs, update `buildHistory.ts` to match.

No global client-state library. Every list/detail page is an `async` Server Component that calls the backend directly (`lib/api.ts`'s `apiFetch`); only the interactive bits (login form, finance button, resolve-mismatch form, trigger-run button, nav sign-out) are small Client Components that call one of a handful of Next.js Route Handlers under `app/api/`, which attach the auth cookie server-side and proxy to the backend.

### Auth

JWT access/refresh tokens are held in httpOnly, secure cookies set by `app/api/login/route.ts` (a Route Handler that calls the backend's `/auth/login`, decodes the access token just to read its `exp` for the cookie's expiry, and sets both cookies) — never exposed to client-side JS, so immune to XSS token theft. `proxy.ts` (Next 16 renamed `middleware.ts` to `proxy.ts` — see decisions log) does a presence-only cookie check to redirect between `/login` and everything else; it does not verify the JWT itself. `/about` is the one explicit exception — no redirect either direction, since it's meant to be viewable without an account. The backend remains the actual authorization boundary (JWT signature verification, RBAC, row-level ownership checks) for every real data request.

Server Components read the cookie directly (`lib/api.ts`'s `currentUser()`/`requireUser()`) and decode the JWT's claims (`lib/auth.ts`) to drive role-based UI (hiding admin-only buttons, the nav's Reconciliation link) — purely cosmetic, not a security boundary. `NavBar` takes `role: string | null` rather than always assuming a logged-in user, since `/about` needs to render a nav bar (site title, "Build Story" link, "Log in" link) for visitors with no session at all — `app/layout.tsx` renders it unconditionally now instead of only when a user cookie is present.

### Design system

Apple-paradigm since 2026-10-11: gray canvas, white grouped cards, system font, one blue tint, status colours only as tinted fills with a glyph, light and dark from the system setting. The full system (tokens, measured contrast, components, rules) is in [`design-apple.md`](design-apple.md). Tokens live in `app/globals.css`; the `.neo-card`/`.neo-btn`/`.neo-input`/`.neo-badge`/`.neo-chip` class names were kept as hooks and restyled, and `tone-*` classes replaced the flat `bg-[var(--color-neo-*)]` fills. Superseded: the 2026-07-18 neo-brutalist style (see the decisions log).

### Backend endpoints this dashboard needed that didn't exist before Phase 8

Every prior `GET` was a single-resource lookup by ID. Three list endpoints were added (TDD, same integration-test rigor as the rest of the backend — see `backend.md`):

- `GET /settlements?status=&page=&size=` — backed by the CQRS read model, row-level filtered for READ_ONLY users via a nullable-parameter JPQL query joined against `LedgerAccount`
- `GET /accounts/{id}/settlements?page=&size=` — one account's settlement history, reusing the existing single-account ownership check
- `GET /invoices?status=&page=&size=` — same nullable-parameter pattern, no CQRS read model needed at this scale

`@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` was added to get a stable paginated JSON shape (`{content, page: {size, number, totalElements, totalPages}}`) instead of relying on `PageImpl`'s serialization, which Spring Data itself warns isn't a stable contract.

### The Lab and the rewritten pages (2026-10-09)

Everything under `/lab` is public (no login; `proxy.ts` treats it like `/about`) and drives a separate sandbox backend, `LAB_BACKEND_URL`. If that is unset the Lab renders a "Lab is not connected" page with the exact `docker compose` command, and nothing else changes. Browser calls never reach the backend directly: `app/api/lab/[...path]` (the Lab's own `/lab/**` endpoints), `app/api/lab-real/[...path]` (the sandbox's real endpoints, called with the chosen persona's token so 200s, 403s and row-level filtering are genuine) and `app/api/lab-persona` (picks a persona) all check the path against a fixed whitelist in `lib/lab/proxyPaths.ts` before any request is made. Cookies never cross: the login cookies only ever go to `BACKEND_URL`, the persona cookie (`lab_persona_token`) only to `LAB_BACKEND_URL`, and the lab proxy forwards no cookies at all (`e2e/isolation/` proves it against two recording stub backends).

Routes: `/lab` (system map with real counters, state machines drawn from the enums, limits and timings read from `/lab/status`, reset, scenario catalog), `/lab/playground`, `/lab/load`, `/lab/chaos`, `/lab/reconciliation` (including the ledger mismatches screen), `/lab/invoices`, `/lab/access`, `/lab/logs`, `/lab/metrics`, `/lab/events` (events and Kafka together), `/lab/traces`, `/lab/alerts`, `/lab/audit`, `/lab/data`, `/lab/correlate`, `/lab/api`. A status strip with every dependency's health sits in the shared Lab layout. Every action has a "Show the request" disclosure (real method, path, headers, body and a curl). One hook, `lib/lab/useLive.ts`, drives every live panel: server-sent events, falling back to 1-second polling if the stream does not open within 3 seconds. Nothing animates unless a real backend event caused it; countdowns only show the distance to a time the server announced. Charts are hand-written SVG (`components/lab/charts.tsx`, `Waterfall.tsx`); there are no new runtime dependencies (`@playwright/test` is a dev dependency).

The login pages (`/settlements`, `/settlements/[id]`, `/accounts/[id]`, `/invoices`, `/invoices/[id]`, `/reconciliation`) were rebuilt on the same primitives (`components/ui`), mobile first, with tables scrolling in their own box. `/reconciliation` now lists open ledger mismatches and resolves them (`app/api/reconciliation/ledger-mismatches/[id]/resolve`), closing the gap that the dashboard had no screen for them; `/accounts/[id]` explains a ledger-inconsistency 500 instead of crashing. These pages use only existing endpoints. Left out because no existing endpoint returns the data: resolved mismatches (the real API lists only OPEN ones; the Lab has a lab-only endpoint) and reconciliation run history.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-10-09 | The Lab talks to a separate backend through whitelisted Route Handlers, not straight from the browser, and keeps its persona token in its own httpOnly cookie | The sandbox backend is not public; a path whitelist at the proxy is a second guard in front of the backend's own; keeping the persona cookie separate from the login cookie lets a test prove the two never cross |
| 2026-10-09 | One `useLive` hook (SSE, falling back to 1 s polling) for every live panel | An Ingress that buffers streams would otherwise leave panels blank; the hook decides by whether the stream opens, not by guessing |
| 2026-10-09 | No chart library | Spec: no new runtime dependencies. The charts are small SVG components with text legends, so colour is never the only signal |
| 2026-10-09 | `react/no-unescaped-entities` turned off in `eslint.config.mjs` | The Lab's explanatory prose uses plain apostrophes and quotes throughout; escaping each one hurts readability for no safety gain |
| 2026-07-11 | Next.js over plain React | Matches recent project experience, gives server-side rendering for the dashboard's data-heavy pages |
| 2026-07-17 | No global client-state library | Server Components fetch directly per page; only a handful of small Client Components exist for interactive actions. Revisit if the dashboard's data needs grow past what per-page fetches can handle |
| 2026-07-17 | Auth token in an httpOnly, secure cookie set by a Route Handler proxy, not client-side storage | Keeps the JWT out of reach of any XSS in the dashboard's own code; the alternative (localStorage) was explicitly rejected for that reason |
| 2026-07-17 | App served under the `/app` path prefix (`basePath` in `next.config.ts`), not at `/` | The backend Ingress (`infra/k8s/06-backend.yaml`) already owns `/accounts`, `/auth`, `/invoices`, `/reconciliation`, `/settlements` as explicit path prefixes on the one shared bare-IP TLS NodePort (30443, no hostname to route by instead) — and this dashboard's own pages live at those same paths. `basePath: "/app"` namespaces the whole frontend so both Ingresses coexist on one origin without colliding. Cost: every client-side `fetch()` call and `proxy.ts`'s manually-constructed redirect URLs need the prefix added by hand (`lib/basePath.ts`) since Next only auto-applies `basePath` to `next/link` and `next/router`, not raw `fetch()` or `NextResponse.redirect(new URL(...))` |
| 2026-07-17 | `middleware.ts` renamed to `proxy.ts` | Next.js 16 deprecated the `middleware` file convention in favor of `proxy` (confirmed via `node_modules/next/dist/docs/`, and via the official `middleware-to-proxy` codemod) — pure rename, same presence-only auth-gate logic |
| 2026-07-17 | Frontend's own Ingress reuses the backend's `backend-tls-secret`, no separate `Certificate` | Same bare IP, same TLS NodePort — a second cert for the same IP would be redundant |
| 2026-07-18 | Whole UI restyled neo-brutalist; a new `/about` "Build Story" page added, public (no login) | User request, aimed at making the live demo readable and interesting to anyone who lands on it without credentials (e.g. a recruiter) — a portfolio project benefits from actually being looked at |
| 2026-07-18 | `/about`'s content lives in `lib/buildHistory.ts`, curated from `PROJECT.md`/`docs/*.md`, not researched separately | Those logs are already the rigorously-maintained engineering record for this whole project (updated every session, per this repo's own discipline) — duplicating that research into the frontend would just create a second, driftable source of truth |
| 2026-07-18 | Design tokens defined via Tailwind v4's `@theme` block rather than hand-written CSS, custom component classes kept independent (no `@apply`-ing one custom class onto another) | Tailwind v4 generates ordinary utilities from `@theme` color/shadow tokens for free; found empirically that its engine rejects `@apply`-ing a custom `@layer components` class inside another one ("Cannot apply unknown utility class") — variants are composed in JSX instead (`className="neo-btn bg-neo-yellow"`) rather than as a second CSS class |
| 2026-10-11 | Whole UI restyled to Apple paradigms (`docs/design-apple.md`), replacing neo-brutalism; dark mode added; Geist via `next/font/google` replaced by the system font stack | User request: same design family as the personal site. Blue chosen as the single tint because yellow/amber already means "pending" in status badges. Dropping `next/font/google` also removes the build's network dependency on Google Fonts (the build failed in an offline sandbox). `.neo-*` class names kept as hooks so the change stays at the token/primitive layer; e2e text assertions untouched |

## Open questions

None currently open. State management (resolved above): plain Server Component fetches, no client store needed at this scale.
