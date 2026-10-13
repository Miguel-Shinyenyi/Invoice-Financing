# MCP server

## Purpose

An MCP server over the settlement engine's Lab sandbox, so an agent can read the engine's settlements, ledger checks, reconciliation state and state machines, and, when an operator allows it, create settlements and resolve mismatches. It exists to show an agent using the engine correctly, and to measure that with evals. It is a separate component in `mcp-server/`. It changes no engine code and adds no endpoint. It talks to the Lab sandbox only (`lab.md`), never to staging and never to real data.

## Current state

Code is in `mcp-server/`: TypeScript on Node 22, stdio transport, `@modelcontextprotocol/sdk` 1.32.1 and zod 4.6.7 (exact pins). Run and install instructions, the tool reference and the client config for Claude Desktop and Claude Code are in `mcp-server/README.md`.

### Tools

Ten read tools, three write tools. The write tools are not registered unless `MCP_ALLOW_WRITES=true`.

| Tool | Kind | Backend call | Persona |
|---|---|---|---|
| `get_system_status` | read | `/lab/status`, `/lab/health`, `/lab/system`, `/lab/personas` | none |
| `get_settlement` | read | `/lab/settlements/{id}/inspect`, `/lab/state-machines` | none |
| `list_settlements` | read | `GET /settlements` | SUPPORT (default) |
| `get_account` | read, side effect | `GET /accounts/{id}`, then `/lab/reconciliation/ledger-mismatches` on a mismatch | SUPPORT (default) |
| `list_account_settlements` | read | `GET /accounts/{id}/settlements` | SUPPORT (default) |
| `list_open_reconciliation_mismatches` | read | `/lab/reconciliation/mismatches` | none |
| `list_ledger_mismatches` | read | `/lab/reconciliation/ledger-mismatches` | none |
| `get_reconciliation_summary` | read | `/lab/reconciliation/summary`, `/stranded`, `/runs` | none |
| `get_invoice` | read | `GET /invoices/{id}` | SUPPORT (default) |
| `explain_state_machine` | read | `/lab/state-machines` | none |
| `create_settlement` | write | `POST /settlements` with `Idempotency-Key` | ADMIN |
| `run_reconciliation` | write | `POST /reconciliation/runs` | ADMIN |
| `resolve_settlement_mismatch` | write | `POST /reconciliation/mismatches/{id}/resolve` | ADMIN |

Also: resources for `reconciliation.md`, `backend.md` and the live OpenAPI document, and two prompts, `investigate-unknown-settlement` and `explain-a-reconciliation-mismatch`.

### What the engine does that shapes the tools

- **Row-level access.** The engine shows the READ_ONLY persona only its own accounts, and the settlements and invoices that touch them. Of the seeded data that persona owns two accounts (`40000000-...-0001`, `-0002`) and two settlements. `/reconciliation/**` is ADMIN and SUPPORT only. So the tools that need the whole sandbox read the Lab's tokenless `/lab/**` views, and the tools that call the real API say in their description what the persona can see.
- **`get_account` is not purely read-only.** `GET /accounts/{id}` runs the ledger check, and on a mismatch it records or refreshes a ledger mismatch. The engine checks before it checks ownership, so a drifted account answers 500 even to a persona that may not read it. The tool turns that 500 into a `MISMATCH` result with stored, computed and difference.
- **Resolving does not stick.** Resolving records a reason and an audit row. The next reconciliation run (5 s in the Lab) opens a new mismatch for the same settlement, because the data still disagrees (Known gap 2). Observed live: the open mismatch got a new id after a resolve.
- **The mismatch API has no `kind` field.** The tool derives one from the stored states: no external state is `MISSING_EXTERNAL_RECORD`, equal states are `AMOUNT_OR_CURRENCY_DIFFERS`, different states are `STATUS_DIFFERS`. These match the three messages in `ReconciliationService`.
- **A settlement can become stranded by time alone.** The seeded PENDING settlement is swept to UNKNOWN about 20 s after a reset. It has no external reference, so the stranded list grows from two to three. Counts of stranded settlements are not fixed.

### Safety rules

Each has a unit test (`test/unit/safety.test.ts`, `auth-and-limits.test.ts`, `leaks.test.ts`, `tools.write.test.ts`, `startup.test.ts`):

1. Startup calls `GET /lab/status` and refuses to run unless the answer is a Lab status snapshot. Pointing it at the Lab's own frontend gave a refusal.
2. The staging host is refused before any request. The list is generated into `src/generated/forbiddenHosts.ts` from `application-demo.yml` and the `LabProperties` default, by `scripts/sync-forbidden-hosts.ts`. `forbiddenHostsSync.test.ts` fails if the generated file differs from either source, or if the two sources differ from each other. A DNS name that resolves to a forbidden host is refused.
3. SUPPORT for reads by default, READ_ONLY if `MCP_READ_PERSONA=READ_ONLY`, ADMIN for writes only. The client refuses to send a read-persona token on anything but a GET, and an ADMIN token on a GET (SUPPORT can write in the engine, so the limit is in code). Tokens are in memory, refreshed 30 s before expiry, re-fetched once after a 401, never logged or returned. The sandbox passwords in `GET /lab/personas` are dropped.
4. Write tools are absent from the tool list by default, and a call by name fails with no request sent.
5. List results default to 20, at most 100, and the response size is capped. Truncation is reported as "showing N of M".
6. A 429 (and a 503 on a read) is retried once after the wait the Lab asks for, at most 3 s. Then the agent gets a rate-limit message with a retry hint. A write is retried only after a 429, which the Lab sends before doing any work.
7. Errors are short. Engine messages are trimmed to 200 characters and dropped if they name a Java class.

### Tests and evals

- **Unit tests** (104): fake HTTP backend with fixtures copied from real Lab responses, an in-memory MCP client, and a scripted fake model for the eval loop.
- **Integration tests** (34): the live Lab, skipped with a message when it is not reachable. Expected values are parsed from `lab/seed/lab-seed.sql` and `scenarios.json`. They cover every read tool, the write tools, the real server over stdio, and a check that each eval case's checker passes a right answer and fails a wrong one.
- **Evals**: `mcp-server/evals/`. Seven cases from the seed with exact expected answers read from the database through the Lab's data browser, not through the MCP server. Limits: 10 cases, 8 turns, 60,000 input and 4,000 output tokens per case, a cost estimate printed first and `--yes` required. One attempt on 2026-10-13 was refused by the Anthropic API for lack of account credit, so no case has been scored (see Open questions).

Counts and commands are in `testing.md`. The MCP safety rules are summarised in `security.md`.

## Decisions log

| Date | Decision | Reason |
|------|----------|--------|
| 2026-10-13 | Branch `feature/mcp-server` was cut from `origin/main`, not `dev` | `dev` has no Lab code (`core/lab`, `lab/seed`, the compose file): the Lab was merged to `main`, and `dev` is an ancestor of `main`. The server needs the Lab |
| 2026-10-13 | Target the Lab sandbox only, with a startup check and a staging refusal | The Lab has public personas and deterministic data, so tool results and evals are repeatable, and nothing real can be touched |
| 2026-10-13 | The forbidden host list is generated from the backend config and guarded by a sync test, not read at runtime | The server may run outside the repo. The same pattern as `AlertRulesSyncTest`: a copy is allowed only if a test proves it matches |
| 2026-10-13 | Reads that need the whole sandbox use the tokenless `/lab/**` views; real-API reads use the SUPPORT persona, or READ_ONLY with `MCP_READ_PERSONA` | The engine's row-level rule hides most seeded data from READ_ONLY. Miguel chose to adapt the "READ_ONLY for reads" rule. SUPPORT sees every row but can also write, so the client sends a read-persona token on GET only. ADMIN stays for writes |
| 2026-10-13 | `get_settlement` uses `/lab/settlements/{id}/inspect` | It is the only endpoint that returns ledger entries, and it works for any settlement |
| 2026-10-13 | `get_system_status` uses `/lab/health` for up or down | `/lab/system` returns flow counters, not service health |
| 2026-10-13 | A drifted account is a normal `MISMATCH` result, not an error | It is a finding. The agent needs stored, computed and the difference, which the 500 body does not give as fields |
| 2026-10-13 | The mismatch `kind` is derived from the stored states and labelled as derived | The API has none. Deriving from the structured fields avoids parsing message text |
| 2026-10-13 | Seed ids are validated with zod `guid`, not `uuid` | The seed ids have version digit 0, so strict RFC 4122 validation rejects them |
| 2026-10-13 | Amount is a string, validated to at most 4 decimals, sent as a number | Avoids float input from an agent and keeps the same request body on every retry, so the engine's request hash matches |
| 2026-10-13 | `create_settlement` never generates an idempotency key | The key is the agent's statement that two calls are one transfer. The tool description teaches reuse on retry |
| 2026-10-13 | Only the exact string `true` enables writes | Truthy variants such as `1` or `yes` should not switch writes on by accident |
| 2026-10-13 | TypeScript 6.0.3, not 7.0.2 | `typescript-eslint` 8.71.2 supports TypeScript below 6.1 only |
| 2026-10-13 | `vite` 8.3.4 is a pinned dev dependency and `mcp-server/.npmrc` sets `legacy-peer-deps=true` | npm 10.9.2 crashes (`Cannot read properties of null (reading 'edgesOut')`) resolving peers when zod and vitest 5 are installed together. With the flag, vitest's `vite` peer is not installed, so it is pinned by hand |
| 2026-10-13 | Evals use exact checks (database state and regular expressions), no model judge | The seed makes expected answers computable. A judge would add a second source of error. The cost is that regular expressions miss unusual wording, so results need reading |
| 2026-10-13 | The idempotency eval executes the first `create_settlement` for real and reports a timeout to the agent | That is what a lost response looks like: the work happened and the answer did not arrive |
| 2026-10-13 | The eval model and prices are environment variables | The model can change without a code edit. The default prices are assumptions and the runner says so |
| 2026-10-13 | Streamable HTTP is out of scope | Stdio is enough for Claude Desktop and Claude Code. HTTP would need auth and hosting decisions |
| 2026-10-13 | Write tools are dropped when `/lab/status` reports `readOnly: true` | A read-only Lab refuses every write. Leaving the tools out is clearer than failing each call |
| 2026-10-13 | Writes against a public Lab are allowed (Miguel's decision) with the flag off by default | The Lab holds fake data and resets every 30 min. Recommended: keep `MCP_ALLOW_WRITES` off in shared configs, and prefer a local Lab for the evals, since the idempotency and resolve cases change state other visitors would see |
| 2026-10-13 | A runner API error stops the evals and is not recorded as a failed case | The first attempt hit a credit-balance error on all 7 cases and printed FAIL. That says nothing about the tools |

## Open questions

- **Base branch.** `dev` cannot build this component, so the branch was cut from `main`. Fast-forwarding `dev` is not a priority (Miguel, 2026-10-13).
- **No `kind` on mismatches.** Should the engine add one? Adding it would be an engine change, which this component may not make.
- **Evals have not been scored.** `ANTHROPIC_API_KEY` is set in Miguel's `.zshrc`. On 2026-10-13 the API answered every call with "credit balance is too low" (HTTP 400), so no model ran and no tokens were spent. They need credit on that account. The default model `claude-sonnet-5-5` and the default prices are unverified until a first run.
- **Writes against a public Lab.** Allowed by Miguel. `MCP_ALLOW_WRITES=true` changes shared state that other visitors see until the next reset (30 min). Open: should the public Lab's reset interval be shortened, or the Lab's own rate limit applied per MCP session?
- **Docs in the build.** `reconciliation.md` and `backend.md` are copied into `dist/docs` at build time. A published package would need them bundled. Not decided because publishing is out of scope.
- **Where the public sandbox runs** is still open (see `lab.md`). The default `LAB_BASE_URL` assumes a local stack.
