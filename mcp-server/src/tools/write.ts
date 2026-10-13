import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { defineTool, guid, type ToolContext } from "./util.js";

type Json = Record<string, unknown>;

/**
 * Write tools. They are registered only when MCP_ALLOW_WRITES=true (see server.ts), and the server
 * has already confirmed at startup that the target is a Lab sandbox. They use the ADMIN persona.
 */
export function registerWriteTools(server: McpServer, ctx: ToolContext): void {
  const { client } = ctx;

  defineTool(server, ctx, {
    name: "create_settlement",
    kind: "write",
    title: "Create a settlement (idempotent)",
    description:
      "Moves an amount between two ledger accounts through the real settlement engine in the Lab sandbox. " +
      "You MUST supply idempotencyKey, a UUID you generate once per logical transfer. This tool never makes one for you. " +
      "Idempotency is the point of the engine: if a call times out or you are unsure whether it went through, call again with the SAME key and the SAME accounts, amount and currency. You get the identical result, with the same settlementId, and no second transfer. " +
      "If you reuse a key with a different body the engine answers with a conflict. If the first call is still running, a repeat can also answer with a conflict: wait a few seconds and repeat again with the same key. Use a new key only for a genuinely new transfer. " +
      "The returned status is the outcome: CONFIRMED, FAILED or UNKNOWN. UNKNOWN means the engine does not know whether the money moved. Do not retry with a new key to 'fix' it: check get_settlement and get_reconciliation_summary instead. " +
      "Only Lab accounts exist here. Seeded accounts with balance include 10000000-0000-0000-0000-000000000001 to -000000000006 (1,000,000 USD each).",
    input: {
      sourceAccountId: guid("Account to debit"),
      destinationAccountId: guid("Account to credit"),
      amount: z
        .string()
        .regex(/^\d{1,13}(\.\d{1,4})?$/, "amount must be a positive decimal such as 12.50")
        .refine((v) => Number(v) > 0, "amount must be greater than zero")
        .describe("Positive decimal as a string, up to 4 decimals, for example 12.50. Send the same text on every retry."),
      currency: z.string().regex(/^[A-Z]{3}$/, "currency must be a 3-letter ISO 4217 code").describe("For example USD. Must match both accounts."),
      idempotencyKey: guid("Idempotency key you generated for this transfer; reuse it on retries"),
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    async run({ sourceAccountId, destinationAccountId, amount, currency, idempotencyKey }) {
      const res = await client.post<Json>("/settlements", {
        role: "ADMIN",
        headers: { "Idempotency-Key": idempotencyKey },
        body: { sourceAccountId, destinationAccountId, amount: Number(amount), currency },
      });
      const r = res.data;
      return {
        summary: `Settlement ${String(r.settlementId)} is ${String(r.status)} for key ${idempotencyKey}. Repeating this call with the same key returns this same settlement.`,
        data: {
          settlementId: r.settlementId,
          status: r.status,
          sourceAccountId: r.sourceAccountId,
          destinationAccountId: r.destinationAccountId,
          amount: r.amount,
          currency: r.currency,
          externalRef: r.externalRef ?? null,
          idempotencyKey,
          createdAt: r.createdAt,
          updatedAt: r.updatedAt,
        },
      };
    },
  });

  defineTool(server, ctx, {
    name: "run_reconciliation",
    kind: "write",
    title: "Run a reconciliation now",
    description:
      "Triggers one reconciliation run in the Lab sandbox, outside the scheduled one (the Lab already runs it every few seconds). It compares settlements that have an external reference with the external system's records and opens a mismatch for each disagreement. Returns the run id, how many records it checked and how many mismatches it found. " +
      "It does not look at UNKNOWN settlements without an external reference (Known gap 1), and it never changes a settlement. " +
      "Use it after you change something and want a fresh comparison. Next: list_open_reconciliation_mismatches.",
    input: {},
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
    async run() {
      const res = await client.post<Json>("/reconciliation/runs", { role: "ADMIN" });
      const r = res.data;
      return {
        summary: `Reconciliation run ${String(r.id)} ${String(r.status)}: checked ${String(r.recordsChecked)}, found ${String(r.mismatchesFound)} mismatches.`,
        data: {
          runId: r.id,
          status: r.status,
          recordsChecked: r.recordsChecked,
          mismatchesFound: r.mismatchesFound,
          startedAt: r.startedAt,
          finishedAt: r.finishedAt,
        },
      };
    },
  });

  defineTool(server, ctx, {
    name: "resolve_settlement_mismatch",
    kind: "write",
    title: "Resolve a settlement mismatch",
    description:
      "Marks one open settlement mismatch RESOLVED, with the reason you give. " +
      "Resolving records the reason, who resolved it and an audit row. It does NOT change the settlement, the ledger or the external record. The underlying disagreement is still there afterwards. This is documented Known gap 2: no endpoint corrects the data, and a human has to fix it by hand. " +
      "Pass the mismatchId from list_open_reconciliation_mismatches, not the settlement id. Resolve only when a human has decided the mismatch is understood. A mismatch that is already resolved answers with a conflict.",
    input: {
      mismatchId: guid("Mismatch id from list_open_reconciliation_mismatches"),
      reason: z.string().trim().min(3).max(500).describe("Why this mismatch is being closed. Recorded in the audit trail."),
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
    async run({ mismatchId, reason }) {
      const res = await client.post<Json>(`/reconciliation/mismatches/${encodeURIComponent(mismatchId)}/resolve`, {
        role: "ADMIN",
        body: { reason },
      });
      const r = res.data;
      return {
        summary: `Mismatch ${mismatchId} is ${String(r.resolutionStatus)}. The settlement and the ledger were not changed.`,
        data: {
          mismatchId: r.id,
          settlementId: r.settlementId,
          resolutionStatus: r.resolutionStatus,
          resolvedAt: r.resolvedAt,
          settlementChanged: false,
          ledgerChanged: false,
          note: "Resolving records a reason and an audit row only (Known gap 2).",
        },
      };
    },
  });
}
