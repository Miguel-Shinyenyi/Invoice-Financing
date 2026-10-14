# Working in this repo

Claude Code reads this file at the start of every task. Keep it short. Detail lives in the files it points to.

Practices that apply to every project are in `~/.claude/CLAUDE.md`. This file adds what is specific here. If the two disagree, this file wins.

## Read in this order

1. `docs/DOCS_MAINTENANCE.md`. It controls how every doc is written and updated. Read it before you change code.
2. Only the docs for the component you are touching. Do not load the rest.

| You are changing | Read |
|---|---|
| Spring Boot API, services, state machine | `docs/backend.md`, `docs/security.md` |
| Tables, columns, migrations | `docs/database.md` |
| Kafka topics, outbox, events | `docs/kafka-events.md` |
| Reconciliation or the ledger check | `docs/reconciliation.md` |
| Fraud service (`ml-service/`) | `docs/fraud-detection.md` |
| Dashboard or Lab UI (`frontend/`) | `docs/frontend.md`, `docs/design-apple.md`, then `frontend/AGENTS.md` |
| Lab sandbox, seed data, load runner | `docs/lab.md`, `lab/seed/README.md` |
| MCP server (`mcp-server/`) | `docs/mcp.md`, `mcp-server/README.md` |
| Pipelines, deploys, environments | `docs/cicd.md`, `docs/kubernetes.md` |
| Tests | `docs/testing.md` |
| Logs, metrics, traces | `docs/observability.md` |

If a task needs a doc that is not in this table, say so and add the row.

## Before you write code

1. Write down how you will check the work. Name the test, command or observable result for each part of the task. Do this before the first line of code.
2. If the task has more than three parts, do the first part only. Run its checks. Report the result and wait. Later parts are described when they are reached.
3. If something in the task disagrees with the code or the docs, stop and say so. Do not pick one silently.

## Rules that already apply

- Every behavior change comes with its doc update in the same change. Docs describe the current state. Delete stale text. Do not comment it out.
- Verify against real code, a real build and a running system. Say which checks you ran and which you did not. Never report an unrun check as passed.
- The Lab sandbox is a separate stack. Never point load, fault injection or the MCP server at staging (`107.155.122.29`) or at real data.
- Do not touch `.github/workflows` unless the task says so.
- Do not push or open a pull request unless the task says so.
- No em dashes in anything you write.

## When you finish

Report: what you built, the real output of each check, what you could not do, anything you changed from the task and why, and open questions.