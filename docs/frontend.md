# Frontend

## Purpose

Describes the Next.js admin dashboard: pages, components, and how it talks to the backend.

## Current state

Not yet built. Planned for Phase 6:

- Transaction list page with filters
- Account detail page with balance and history
- Reconciliation view
- Role-based UI: admin actions hidden from non-admin users

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-07-11 | Next.js over plain React | Matches recent project experience, gives server-side rendering for the dashboard's data-heavy pages |

## Open questions

- State management approach: React Query alone, or add a global store. Decide once the dashboard's data needs are clearer.
