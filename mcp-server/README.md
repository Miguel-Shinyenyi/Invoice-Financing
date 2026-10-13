# Settlement engine MCP server

An MCP server over the settlement engine's **Lab sandbox**. An agent can read the engine's
settlements, ledger checks, reconciliation state and state machines, and (only if you allow it)
create settlements and resolve mismatches. It talks to the Lab only: a separate stack with seeded
fake data, never staging, never real data.

Design notes and decisions are in [`docs/mcp.md`](../docs/mcp.md). The Lab itself is in
[`docs/lab.md`](../docs/lab.md).

## Run it from a clean clone

You need Node 22 or newer and Docker.

```
git clone <this repository>
cd Invoice-Financing

# 1. Start the Lab sandbox (backend on 127.0.0.1:8080). The first run builds the images.
docker compose -f infra/docker-compose.lab.yml up -d

# 2. Build the server
cd mcp-server
npm ci
npm run build

# 3. Try it: a real MCP client calls every tool once over stdio
npm run demo
```

`node dist/index.js` speaks MCP on stdio. It is meant to be started by an MCP client, not by hand.
The tests read `lab/seed/` and the backend config, so they need the whole repository checkout.

## Add it to Claude Desktop

Edit `claude_desktop_config.json` and use an absolute path:

```json
{
  "mcpServers": {
    "settlement-engine-lab": {
      "command": "node",
      "args": ["/absolute/path/to/Invoice-Financing/mcp-server/dist/index.js"],
      "env": { "LAB_BASE_URL": "http://127.0.0.1:8080" }
    }
  }
}
```

## Add it to Claude Code

```
claude mcp add settlement-engine-lab -e LAB_BASE_URL=http://127.0.0.1:8080 -- node /absolute/path/to/Invoice-Financing/mcp-server/dist/index.js
```

Add `-e MCP_ALLOW_WRITES=true` to enable the three write tools. Or commit this as `.mcp.json`:

```json
{
  "mcpServers": {
    "settlement-engine-lab": {
      "command": "node",
      "args": ["mcp-server/dist/index.js"],
      "env": { "LAB_BASE_URL": "http://127.0.0.1:8080" }
    }
  }
}
```

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `LAB_BASE_URL` | `http://127.0.0.1:8080` | The Lab backend. Refused if it is the staging host or does not answer as a Lab sandbox. |
| `MCP_ALLOW_WRITES` | off | Only the exact value `true` registers the write tools, and not even then if the Lab reports read-only mode. `1`, `yes` and `TRUE` do not. |
| `MCP_READ_PERSONA` | `SUPPORT` | Persona for reads through the real API: `SUPPORT` (sees every row) or `READ_ONLY` (sees only its own two accounts). |
| `MCP_MAX_RESPONSE_CHARS` | `24000` | Largest tool result. A longer one is cut and says so. |
| `MCP_REQUEST_TIMEOUT_MS` | `10000` | Timeout for each call to the Lab. |
| `ANTHROPIC_API_KEY` | none | Evals only. Read from the environment, never written to a file. |
| `EVAL_MODEL` | `claude-sonnet-5-5` | Evals only. The model the eval agent uses. |
| `EVAL_PRICE_INPUT_PER_MTOK`, `EVAL_PRICE_OUTPUT_PER_MTOK` | `3`, `15` | Evals only. USD per million tokens, used for the cost estimate. Assumptions: check current pricing. |

## Tools

Every result is compact JSON plus a one-line summary. Lists default to 20 rows, at most 100, and a
cut list says "showing 20 of 134".

| Tool | Kind | Returns |
|---|---|---|
| `get_system_status` | read | Lab health, the compressed timers, reset time, caps, per-service up or down, flow counters, the personas and the read persona in use. Start here. |
| `get_settlement` | read | One settlement: status, amount, accounts, external reference, ledger entries, mismatches, and what the status means with the transitions read from the engine. |
| `list_settlements` | read | Settlements from the read model, filtered by status. Every settlement as SUPPORT (default); only the READ_ONLY persona's own with `MCP_READ_PERSONA=READ_ONLY`. |
| `get_account` | read, **side effect** | Balance and ledger check. The engine records a ledger mismatch when the account is inconsistent, so call it once per account. |
| `list_account_settlements` | read | An account's settlements. Any account as SUPPORT; own accounts only as READ_ONLY. |
| `list_open_reconciliation_mismatches` | read | Open settlement mismatches with the engine side, the external side and a derived kind. |
| `list_ledger_mismatches` | read | Accounts whose stored balance disagrees with their entries: stored, computed, difference. |
| `get_reconciliation_summary` | read | Open counts, recent runs, and stranded UNKNOWN settlements (Known gap 1). |
| `get_invoice` | read | An invoice and its advance. Any invoice as SUPPORT; none of the seeded ones as READ_ONLY. |
| `explain_state_machine` | read | Settlement, invoice and advance states and transitions, read from the engine. |
| `create_settlement` | **write** | Moves an amount between two accounts. Requires an `idempotencyKey` you supply. Same key and body returns the same settlement. |
| `run_reconciliation` | **write** | Triggers one reconciliation run and returns its summary. |
| `resolve_settlement_mismatch` | **write** | Marks a mismatch RESOLVED with a reason. Changes no settlement or ledger data (Known gap 2). |

Also served: three resources (`settlement-engine://docs/reconciliation`,
`settlement-engine://docs/backend`, `settlement-engine://openapi`) and two prompts
(`investigate-unknown-settlement`, `explain-a-reconciliation-mismatch`).

## Safety rules

Each one has a test in `test/unit/`.

1. **Lab only.** On startup the server calls `GET /lab/status`. If the target does not answer as a Lab sandbox it exits with a reason.
2. **Staging is refused** before any request. The forbidden host list is generated from the backend's own config (`settlement-engine.demo.forbidden-hosts`), and a test fails if the two drift. A DNS name that resolves to a forbidden host is refused too.
3. **Personas, not secrets.** Tokens come from `POST /lab/personas/{role}/token`: SUPPORT for reads by default (set `MCP_READ_PERSONA=READ_ONLY` for the narrow view), ADMIN for writes only. A read-persona token is sent on GET only and an ADMIN token never on a GET, enforced in the client and tested. They live in memory, refresh before they expire, and never appear in a log or a result. The sandbox passwords the backend lists are dropped.
4. **Writes are not registered** unless `MCP_ALLOW_WRITES=true`. A test proves they are absent from the tool list.
5. **Capped results.** Rows default to 20, at most 100. The response size is capped. Truncation is always reported.
6. **The Lab's limits are respected.** On a 429 or 503 the server waits as told (at most 3 seconds) and retries once. Then it returns a clear message with a retry hint. A write is never retried after a 503.
7. **Short errors.** No stack traces, tokens or raw upstream bodies. Messages from the engine are trimmed and screened for class names.

## Tests

```
npm run lint          # tsc --noEmit and eslint
npm run build
npm test              # unit and integration
npm run test:unit     # no Docker needed: a fake backend
npm run test:integration
```

- **Unit tests** (`test/unit/`) use a fake HTTP backend whose fixtures are copies of real Lab responses.
- **Integration tests** (`test/integration/`) run against the live Lab. If it is not reachable they skip and say so, so `npm test` never fails because Docker is off. Expected values are parsed from `lab/seed/lab-seed.sql`, not typed in. They include one write test: the same key twice gives one settlement and the same id twice.

## Evals

Two ways to run the same cases and the same checkers. `--agent api` (the default) uses the Anthropic API and bills per token. `--agent claude-cli` runs each case through the `claude` CLI with only this server attached, so it uses your own login and counts against your plan's usage, with no per-token charge. The CLI gets no built-in tools. `ANTHROPIC_API_KEY` is removed from its environment, because with a key set the CLI would use the API credit instead.

```
npm run evals -- --agent claude-cli --yes
```

In CLI mode the model is the CLI's default (set `EVAL_CLI_MODEL` to choose one), the turn cap is enforced, and the per-case token budget is not. A small proxy (`evals/proxy.ts`) sits between the CLI and the server to simulate the lost response in the retry case.

The evals measure whether an agent uses the tools correctly. They are separate from the tests and need
a model.

```
export ANTHROPIC_API_KEY=...        # your shell only, never a file
npm run evals                       # prints the cost estimate and stops
npm run evals -- --yes              # runs the cases and spends tokens
npm run evals -- --yes --only open-mismatches,trap-staging
```

- Seven cases, at most 10 (the API mode lines below; the CLI mode shares the cases and checkers). At most 8 turns each. A token budget per case (60,000 in, 4,000 out). Without `ANTHROPIC_API_KEY` the runner prints a message and exits with code 2 without doing anything.
- Each case has an exact, checkable answer computed from the sandbox database or the seed file. There is no model judge. The checks are regular expressions on the final answer plus database state, so read the results file and not only the pass count.
- Two cases need the write tools: resolving a mismatch changes nothing, and a retry with the same key leaves one settlement. In the retry case the runner executes the first `create_settlement` for real and then tells the agent it timed out.
- Two cases are traps: an instruction to edit a balance directly, and a request for staging data. The right behaviour is to say no.
- The runner resets the Lab once at the start. Results go to `evals/results/<date>-<model>.json`, with per-case pass or fail, tools in order, turns and tokens.
- `test/integration/d-eval-checkers.integration.test.ts` checks the checkers: it feeds each case a scripted correct run and a scripted wrong run against the live database.
