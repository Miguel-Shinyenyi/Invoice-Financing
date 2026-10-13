import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { ToolError } from "../errors.js";
import { fitList, readPage, resolveLimit, toCamel } from "../format.js";
import { defineTool, guid, limitField, pageField, READ_ANNOTATIONS, type ToolContext } from "./util.js";

type Json = Record<string, unknown>;

interface StateInfo {
  name: string;
  terminal: boolean;
  noCodePath: boolean;
  note: string | null;
}
interface Machine {
  states: StateInfo[];
  transitions: { from: string; to: string }[];
}

/**
 * What each settlement status means, in words. These sentences describe; they are not the rules.
 * Which transitions exist, which states are terminal and which have no code path are always read
 * from GET /lab/state-machines at call time.
 */
const SETTLEMENT_MEANING: Record<string, string> = {
  PENDING:
    "The engine recorded the request and has not finalized it. A settlement stuck here is moved to UNKNOWN by the stale sweep after a grace period.",
  CONFIRMED: "The external system confirmed the transfer and the engine wrote the ledger entries.",
  FAILED: "The transfer did not happen. No ledger entries were written for it.",
  UNKNOWN:
    "The engine could not tell whether the transfer happened, for example after a lost response. Reconciliation can only resolve it when it has an external reference.",
  REVERSED: "A confirmed settlement that was reversed. The state machine allows it, but no code path produces it.",
};

function describeStatus(status: string, machine: Machine | undefined) {
  const state = machine?.states.find((s) => s.name === status);
  const next = (machine?.transitions ?? []).filter((t) => t.from === status).map((t) => t.to);
  const parts = [SETTLEMENT_MEANING[status] ?? `Status ${status}.`];
  if (state) {
    parts.push(
      state.terminal
        ? "It is terminal: the engine does not move it to another status."
        : next.length > 0
          ? `From here the engine can move it to: ${next.join(", ")}.`
          : "No transitions leave it.",
    );
    if (state.noCodePath && state.note) parts.push(state.note);
  }
  return {
    status,
    meaning: parts.join(" "),
    terminal: state?.terminal ?? null,
    possibleNext: next,
    noCodePath: state?.noCodePath ?? null,
  };
}

function difference(stored: unknown, computed: unknown): number | null {
  if (typeof stored !== "number" || typeof computed !== "number") return null;
  return Math.round((stored - computed) * 10_000) / 10_000;
}

/**
 * The mismatch API has no `kind` field. The kind follows from the two states the engine stores:
 * no external state means no record was found, equal states mean the amount or currency differed,
 * different states mean the status differed.
 */
function mismatchKind(internalState: unknown, externalState: unknown): string {
  if (externalState === null || externalState === undefined) return "MISSING_EXTERNAL_RECORD";
  return internalState === externalState ? "AMOUNT_OR_CURRENCY_DIFFERS" : "STATUS_DIFFERS";
}

export function registerReadTools(server: McpServer, ctx: ToolContext): void {
  const { client, config } = ctx;

  defineTool(server, ctx, {
    name: "get_system_status",
    kind: "read",
    title: "Lab status and health",
    description:
      "Start here. Returns what the Lab sandbox is doing right now: the compressed timers (reconciliation and the stale sweep run every few seconds here, not minutes), the next scheduled reset, the load caps, whether each dependency (backend, ml-service, postgres, kafka, jaeger, prometheus, alertmanager) is up, per-stage counters of the settlement flow, and the personas with the accounts the READ_ONLY persona owns. " +
      "Use it to confirm the sandbox is healthy before investigating, and to learn which accounts the account tools can read. It reads only. The sandbox resets to its seed data every 30 minutes, so ids from an earlier session may be gone. " +
      "Next: get_reconciliation_summary for open problems, or explain_state_machine for the rules.",
    input: {},
    annotations: READ_ANNOTATIONS,
    async run() {
      const [status, health, system, personas] = await Promise.all([
        client.get<Json>("/lab/status"),
        client.get<{ checks?: Json[] }>("/lab/health"),
        client.get<{ nodes?: Json[] }>("/lab/system"),
        client.get<Json[]>("/lab/personas"),
      ]);
      const s = status.data;
      const services = (health.data.checks ?? []).map((c) => ({
        name: c.name,
        status: c.status,
        latencyMs: c.latencyMs ?? null,
      }));
      const down = services.filter((c) => c.status !== "UP").map((c) => String(c.name));
      const caps = (s.caps ?? {}) as Json;
      const reset = (s.reset ?? {}) as Json;
      return {
        summary: `Lab sandbox is ${down.length === 0 ? "healthy: all " + services.length + " services up" : "degraded: down: " + down.join(", ")}; read-only mode ${s.readOnly ? "on" : "off"}.`,
        data: {
          sandbox: true,
          now: s.now,
          readOnlyMode: s.readOnly,
          timeCompression: s.timeCompression,
          next: s.next,
          reset: { lastResetAt: reset.lastResetAt, nextResetAt: reset.nextResetAt, autoResetMinutes: reset.autoResetMinutes },
          caps,
          services,
          flowCounters: (system.data.nodes ?? []).map((n) => ({ stage: n.id, count: n.count, lastEventAt: n.lastEventAt })),
          // The backend also returns each persona's sandbox password. It is never passed on.
          personas: (Array.isArray(personas.data) ? personas.data : []).map((p) => ({
            role: p.role,
            username: p.username,
            ownedAccounts: p.ownedAccounts,
          })),
        },
      };
    },
  });

  defineTool(server, ctx, {
    name: "get_settlement",
    kind: "read",
    title: "Get one settlement",
    description:
      "Returns one settlement by id: status, amount, currency, source and destination account, external reference (if any), the ledger entries it wrote, any reconciliation or ledger mismatch recorded for it, whether the external system holds a record, and a plain-language note on what the status means and which transitions are possible. The transition rules are read from the engine's state machine at call time. " +
      "Use it to understand one settlement. Do not use it to list settlements (use list_settlements) or to find all stranded ones (use get_reconciliation_summary). " +
      "A settlement in UNKNOWN with no external reference is stranded: reconciliation never looks at it (Known gap 1). " +
      "Reads the Lab's inspector view, so it works for any settlement, not only the READ_ONLY persona's.",
    input: { settlementId: guid("Settlement id") },
    annotations: READ_ANNOTATIONS,
    async run({ settlementId }) {
      const [inspect, machines] = await Promise.all([
        client.get<Json>(`/lab/settlements/${encodeURIComponent(settlementId)}/inspect`),
        client.get<{ settlement?: Machine }>("/lab/state-machines"),
      ]);
      const d = inspect.data;
      const row = toCamel((d.settlement ?? {}) as Json);
      const status = String(row.status ?? "");
      const external = (d.external ?? {}) as Json;
      const stranded = status === "UNKNOWN" && (row.externalRef === null || row.externalRef === undefined);
      const entries = ((d.ledgerEntries ?? []) as Json[]).map((e) => ({
        accountId: e.account_id,
        entryType: e.entry_type,
        amount: e.amount,
        createdAt: e.created_at,
      }));
      const mismatches = ((d.mismatches ?? []) as Json[]).map(toCamel);
      const ledgerMismatches = ((d.ledgerMismatches ?? []) as Json[]).map(toCamel);
      return {
        summary: `Settlement ${settlementId} is ${status}${stranded ? " and stranded (no external reference, so reconciliation never sees it)" : ""}; ${entries.length} ledger entries, ${mismatches.length} mismatches.`,
        data: {
          settlement: {
            id: row.id,
            sourceAccountId: row.sourceAccountId,
            destinationAccountId: row.destinationAccountId,
            amount: row.amount,
            currency: row.currency,
            status,
            externalRef: row.externalRef ?? null,
            idempotencyKey: row.idempotencyKey,
            createdAt: row.createdAt,
            updatedAt: row.updatedAt,
          },
          statusInfo: describeStatus(status, machines.data.settlement),
          stranded,
          strandedExplanation: stranded
            ? "UNKNOWN with no external reference. The reconciliation run only loads settlements that have a reference, so it will never resolve this one. This is Known gap 1."
            : null,
          ledgerEntries: entries,
          reconciliationMismatches: mismatches,
          ledgerMismatches,
          external: { externalRef: external.externalRef ?? null, recordHeld: external.recordHeld ?? null },
          outboxTopics: ((d.outboxEvents ?? []) as Json[]).map((e) => e.topic),
        },
      };
    },
  });

  defineTool(server, ctx, {
    name: "list_settlements",
    kind: "read",
    title: "List settlements",
    description:
      "Lists settlements from the engine's read model, newest first, optionally filtered by status (for example CONFIRMED or UNKNOWN; see explain_state_machine for the names). Returns id, accounts, amount, currency, status and update time per row, with a 'showing N of M' notice when the list is cut. " +
      "Visibility is limited: this uses the READ_ONLY persona, and the engine only shows that persona settlements that touch accounts it owns (see get_system_status). For a sandbox-wide view of problems use get_reconciliation_summary. " +
      "Next: get_settlement for one row.",
    input: {
      status: z
        .string()
        .regex(/^[A-Z_]{3,20}$/)
        .optional()
        .describe("Optional status filter, upper case, for example UNKNOWN."),
      limit: limitField,
      page: pageField,
    },
    annotations: READ_ANNOTATIONS,
    async run({ status, limit, page }) {
      const size = resolveLimit(limit, config);
      const res = await client.get("/settlements", {
        role: "READ_ONLY",
        query: { status, page: page ?? 0, size },
      });
      const p = readPage(res.data);
      const fit = fitList(p.content, p.total, size, config);
      return {
        summary: `Showing ${fit.shown} of ${p.total} settlements${status ? " with status " + status : ""}, page ${p.page + 1} of ${Math.max(p.totalPages, 1)}.`,
        data: {
          items: fit.items,
          shown: fit.shown,
          totalMatching: p.total,
          page: p.page,
          totalPages: p.totalPages,
          nextPage: p.page + 1 < p.totalPages ? p.page + 1 : null,
          notice: fit.notice ?? null,
          visibility: "Only settlements touching accounts owned by the READ_ONLY persona.",
        },
      };
    },
  });

  defineTool(server, ctx, {
    name: "get_account",
    kind: "read",
    title: "Get an account and run its ledger check",
    description:
      "Returns an account's balance and currency and the result of the ledger consistency check (stored balance against the net of its ledger entries). " +
      "NOT purely read-only: the engine runs the ledger check on every read of the account, and when the stored balance disagrees with the entries it records or refreshes a ledger mismatch for manual review. Reading a drifted account creates that record. Call it once per account and do not poll it. " +
      "When the account is inconsistent the result has ledgerCheck MISMATCH with storedBalance, computedBalance and the difference, instead of an error. " +
      "Access: the READ_ONLY persona may read only accounts it owns (see get_system_status). Any other consistent account returns access_denied. A drifted account still shows its mismatch, because the engine checks before it checks ownership. " +
      "Next: list_ledger_mismatches for the open record, list_account_settlements for history.",
    input: { accountId: guid("Account id") },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    async run({ accountId }) {
      try {
        const res = await client.get<Json>(`/accounts/${encodeURIComponent(accountId)}`, { role: "READ_ONLY" });
        return {
          summary: `Account ${accountId} balance ${String(res.data.balance)} ${String(res.data.currency)}; ledger check passed.`,
          data: {
            id: res.data.id,
            ownerId: res.data.ownerId,
            balance: res.data.balance,
            currency: res.data.currency,
            createdAt: res.data.createdAt,
            ledgerCheck: "CONSISTENT",
            sideEffectNote: "The ledger check ran and found no mismatch, so nothing was recorded.",
          },
        };
      } catch (err) {
        if (!(err instanceof ToolError) || err.internal.status !== 500 || !/inconsistent/i.test(err.internal.detail ?? "")) {
          throw err;
        }
        const open = await client.get<Json[]>("/lab/reconciliation/ledger-mismatches", { query: { status: "OPEN" } });
        const row = (Array.isArray(open.data) ? open.data : []).find((m) => m.accountId === accountId);
        const stored = row?.storedBalance;
        const computed = row?.computedBalance;
        return {
          summary: `Account ${accountId} FAILS the ledger check: stored ${String(stored)}, entries net ${String(computed)}.`,
          data: {
            id: accountId,
            ledgerCheck: "MISMATCH",
            storedBalance: stored ?? null,
            computedBalance: computed ?? null,
            difference: difference(stored, computed),
            mismatchId: row?.id ?? null,
            sideEffectNote:
              "The engine recorded (or refreshed) an OPEN ledger mismatch for this account while checking it. No tool corrects the balance. Resolving a ledger mismatch only records a reason.",
            engineMessage: err.internal.detail,
          },
        };
      }
    },
  });

  defineTool(server, ctx, {
    name: "list_account_settlements",
    kind: "read",
    title: "List an account's settlements",
    description:
      "Lists the settlements where an account is the source or the destination, from the read model, with a 'showing N of M' notice. " +
      "Access: the READ_ONLY persona may read only accounts it owns (see get_system_status); other accounts return access_denied. Does not run the ledger check. " +
      "Next: get_settlement for one row.",
    input: { accountId: guid("Account id"), limit: limitField, page: pageField },
    annotations: READ_ANNOTATIONS,
    async run({ accountId, limit, page }) {
      const size = resolveLimit(limit, config);
      const res = await client.get(`/accounts/${encodeURIComponent(accountId)}/settlements`, {
        role: "READ_ONLY",
        query: { page: page ?? 0, size },
      });
      const p = readPage(res.data);
      const fit = fitList(p.content, p.total, size, config);
      return {
        summary: `${fit.items.length} settlements for account ${accountId}${fit.notice ? " (" + fit.notice + ")" : ""}; ${p.total} in total.`,
        data: { items: fit.items, shown: fit.shown, total: p.total, page: p.page, totalPages: p.totalPages, notice: fit.notice ?? null },
      };
    },
  });

  defineTool(server, ctx, {
    name: "list_open_reconciliation_mismatches",
    kind: "read",
    title: "Open settlement mismatches",
    description:
      "Lists open reconciliation mismatches: settlements whose engine record disagrees with the external system's record. Each row has the settlement id, the engine's state (internalState), the external side (externalState, or null when no external record exists), the engine's own details text, and a kind derived by this server from those two states: MISSING_EXTERNAL_RECORD, AMOUNT_OR_CURRENCY_DIFFERS or STATUS_DIFFERS. The engine API has no kind field. " +
      "Use it to see what needs a human. Does not include stranded UNKNOWN settlements, because reconciliation never opens a mismatch for them (use get_reconciliation_summary). " +
      "Next: get_settlement with the settlementId, then resolve_settlement_mismatch if writes are enabled and a human has decided.",
    input: { limit: limitField },
    annotations: READ_ANNOTATIONS,
    async run({ limit }) {
      const size = resolveLimit(limit, config);
      const res = await client.get<Json[]>("/lab/reconciliation/mismatches", { query: { status: "OPEN" } });
      const rows = (Array.isArray(res.data) ? res.data : []).map((m) => ({
        mismatchId: m.id,
        settlementId: m.settlementId,
        kind: mismatchKind(m.internalState, m.externalState),
        internalState: m.internalState,
        externalState: m.externalState ?? null,
        details: m.details,
        createdAt: m.createdAt,
      }));
      const fit = fitList(rows, rows.length, size, config);
      return {
        summary: `${rows.length} open settlement mismatch${rows.length === 1 ? "" : "es"}${fit.notice ? " (" + fit.notice + ")" : ""}.`,
        data: { items: fit.items, shown: fit.shown, total: rows.length, notice: fit.notice ?? null },
      };
    },
  });

  defineTool(server, ctx, {
    name: "list_ledger_mismatches",
    kind: "read",
    title: "Ledger mismatches",
    description:
      "Lists ledger consistency mismatches: accounts whose stored balance disagrees with the net of their ledger entries. Each row has the account id, the stored balance, the computed balance from the entries, and the difference (stored minus computed). Defaults to OPEN ones; pass status RESOLVED for closed ones. " +
      "A ledger mismatch is found when an account is read (see get_account), not by a scheduled run. Resolving one records a reason and does not change the balance. " +
      "Next: get_account to see the check for one account.",
    input: {
      status: z.enum(["OPEN", "RESOLVED"]).optional().describe("Default OPEN."),
      limit: limitField,
    },
    annotations: READ_ANNOTATIONS,
    async run({ status, limit }) {
      const size = resolveLimit(limit, config);
      const res = await client.get<Json[]>("/lab/reconciliation/ledger-mismatches", { query: { status: status ?? "OPEN" } });
      const rows = (Array.isArray(res.data) ? res.data : []).map((m) => ({
        mismatchId: m.id,
        accountId: m.accountId,
        storedBalance: m.storedBalance,
        computedBalance: m.computedBalance,
        difference: difference(m.storedBalance, m.computedBalance),
        resolutionStatus: m.resolutionStatus,
        details: m.details,
        createdAt: m.createdAt,
      }));
      const fit = fitList(rows, rows.length, size, config);
      return {
        summary: `${rows.length} ${(status ?? "OPEN").toLowerCase()} ledger mismatch${rows.length === 1 ? "" : "es"}${fit.notice ? " (" + fit.notice + ")" : ""}.`,
        data: { items: fit.items, shown: fit.shown, total: rows.length, notice: fit.notice ?? null },
      };
    },
  });

  defineTool(server, ctx, {
    name: "get_reconciliation_summary",
    kind: "read",
    title: "Reconciliation summary and stranded settlements",
    description:
      "Returns the state of reconciliation in one call: how many settlement mismatches and ledger mismatches are open, the most recent reconciliation runs, and the stranded UNKNOWN settlements. " +
      "A stranded UNKNOWN settlement has no external reference, so the reconciliation run never loads it and never opens a mismatch for it. Nothing resolves it. This is documented Known gap 1, and it is why this list can be non-empty while open mismatches is zero. " +
      "Nothing alerts a human about a mismatch either (Known gap 6). " +
      "Next: get_settlement for a stranded id, list_open_reconciliation_mismatches or list_ledger_mismatches for detail.",
    input: { limit: limitField },
    annotations: READ_ANNOTATIONS,
    async run({ limit }) {
      const size = resolveLimit(limit, config);
      const [summary, stranded, runs] = await Promise.all([
        client.get<Json>("/lab/reconciliation/summary"),
        client.get<{ settlements?: Json[]; explanation?: string }>("/lab/reconciliation/stranded"),
        client.get<Json[]>("/lab/reconciliation/runs", { query: { limit: 5 } }),
      ]);
      const list = stranded.data.settlements ?? [];
      const fit = fitList(
        list.map((s) => ({
          settlementId: s.settlementId,
          sourceAccountId: s.sourceAccountId,
          destinationAccountId: s.destinationAccountId,
          amount: s.amount,
          currency: s.currency,
          status: s.status,
          externalRef: s.externalRef ?? null,
          updatedAt: s.updatedAt,
        })),
        list.length,
        size,
        config,
        1500,
      );
      const sm = summary.data;
      return {
        summary: `${String(sm.openMismatches)} open settlement mismatches, ${String(sm.openLedgerMismatches)} open ledger mismatches, ${list.length} stranded UNKNOWN settlements.`,
        data: {
          openSettlementMismatches: sm.openMismatches,
          openLedgerMismatches: sm.openLedgerMismatches,
          unseenMismatches: sm.unseenMismatches,
          strandedUnknownCount: list.length,
          strandedUnknown: fit.items,
          strandedNotice: fit.notice ?? null,
          strandedExplanation: stranded.data.explanation ?? null,
          alerting: sm.note,
          recentRuns: (Array.isArray(runs.data) ? runs.data : []).map((r) => ({
            id: r.id,
            startedAt: r.startedAt,
            recordsChecked: r.recordsChecked,
            mismatchesFound: r.mismatchesFound,
            status: r.status,
          })),
        },
      };
    },
  });

  defineTool(server, ctx, {
    name: "get_invoice",
    kind: "read",
    title: "Get an invoice",
    description:
      "Returns an invoice and its advance: customer reference, amount, due date, status, and the advance with its fee, the disbursing settlement id and the repaying settlement id (null until repaid). " +
      "Access: the READ_ONLY persona may read only invoices of business accounts it owns. In the seed data none of the invoices belong to it, so expect access_denied for seeded invoice ids; that is the engine's row-level rule, not a fault. " +
      "Next: get_settlement with the disbursedSettlementId, or explain_state_machine for invoice and advance states.",
    input: { invoiceId: guid("Invoice id") },
    annotations: READ_ANNOTATIONS,
    async run({ invoiceId }) {
      const res = await client.get<Json>(`/invoices/${encodeURIComponent(invoiceId)}`, { role: "READ_ONLY" });
      const advance = (res.data.advance ?? null) as Json | null;
      return {
        summary: `Invoice ${invoiceId} is ${String(res.data.status)}${advance ? ", advance " + String(advance.status) : ", no advance"}.`,
        data: { invoice: res.data },
      };
    },
  });

  defineTool(server, ctx, {
    name: "explain_state_machine",
    kind: "read",
    title: "Explain a state machine",
    description:
      "Returns the states and allowed transitions for the settlement, invoice and advance state machines, read from the engine at call time. Each state says whether it is terminal and whether the engine has any code path that reaches it. States with no code path (REVERSED for settlements, DEFAULTED for advances) are allowed by the state machine but nothing ever sets them: Known gaps 3 and similar. " +
      "Use it before reasoning about what a status means or what can happen next. It reads only.",
    input: {
      machine: z.enum(["settlement", "invoice", "advance", "all"]).optional().describe("Which machine. Default all."),
    },
    annotations: READ_ANNOTATIONS,
    async run({ machine }) {
      const res = await client.get<Record<string, Machine>>("/lab/state-machines");
      const want = machine && machine !== "all" ? [machine] : ["settlement", "invoice", "advance"];
      const out: Record<string, unknown> = {};
      for (const name of want) {
        const m = res.data[name];
        if (!m) continue;
        out[name] = {
          states: m.states.map((s) => ({ name: s.name, terminal: s.terminal, noCodePath: s.noCodePath, note: s.note })),
          transitions: m.transitions.map((t) => `${t.from} -> ${t.to}`),
        };
      }
      return { summary: `State machine${want.length === 1 ? "" : "s"}: ${want.join(", ")}.`, data: { machines: out } };
    },
  });
}
