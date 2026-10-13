import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";

export function registerPrompts(server: McpServer): void {
  server.registerPrompt(
    "investigate-unknown-settlement",
    {
      title: "Investigate an UNKNOWN settlement",
      description: "A guided workflow for working out why a settlement is UNKNOWN and whether anything will resolve it.",
      argsSchema: { settlementId: z.guid().optional().describe("The settlement to investigate. Leave out to pick one from the stranded list.") },
    },
    ({ settlementId }) => ({
      messages: [
        {
          role: "user",
          content: {
            type: "text",
            text: [
              settlementId
                ? `Investigate settlement ${settlementId}, which is reported as UNKNOWN.`
                : "Investigate an UNKNOWN settlement in the Lab sandbox. Pick one from the stranded list.",
              "Work through these tools in order and stop when you can answer:",
              "1. get_system_status: confirm the sandbox is healthy and note the compressed timers.",
              settlementId
                ? `2. get_settlement with settlementId ${settlementId}: read the status, the externalRef and the status note.`
                : "2. get_reconciliation_summary: pick a stranded UNKNOWN settlement, then call get_settlement on it.",
              "3. Check the externalRef. If it is null, the settlement is stranded: reconciliation only loads settlements that have a reference, so nothing will ever resolve it (Known gap 1). Say so plainly.",
              "4. If it has an externalRef, call list_open_reconciliation_mismatches and look for its settlement id. If there is none, a reconciliation run will resolve it once the grace period passes; run_reconciliation can trigger one if writes are enabled.",
              "5. explain_state_machine with machine settlement: confirm which transitions leave UNKNOWN.",
              "Report: the status, whether it is stranded, what can happen next, and what a human has to do. Do not create a new settlement to work around it, and do not claim anything was fixed unless a tool result shows it.",
            ].join("\n"),
          },
        },
      ],
    }),
  );

  server.registerPrompt(
    "explain-a-reconciliation-mismatch",
    {
      title: "Explain a reconciliation mismatch",
      description: "A guided workflow for explaining one open reconciliation mismatch to a person.",
      argsSchema: { mismatchId: z.guid().optional().describe("The mismatch to explain. Leave out to take the first open one.") },
    },
    ({ mismatchId }) => ({
      messages: [
        {
          role: "user",
          content: {
            type: "text",
            text: [
              mismatchId
                ? `Explain reconciliation mismatch ${mismatchId}.`
                : "Explain the first open reconciliation mismatch in the Lab sandbox.",
              "Work through these tools in order:",
              "1. list_open_reconciliation_mismatches: find the mismatch. Read its kind, internalState, externalState and details.",
              "2. get_settlement with the mismatch's settlementId: read the engine's amount, status, externalRef and ledger entries.",
              "3. Explain in plain words which side disagrees and how: a missing external record, a different amount or currency, or a different status.",
              "4. State what resolving does. Resolving records a reason and an audit row. It does not change the settlement or the ledger (Known gap 2), so the disagreement stays until someone fixes the data by hand.",
              "Do not call resolve_settlement_mismatch unless the person asks you to and gives a reason.",
            ].join("\n"),
          },
        },
      ],
    }),
  );
}
