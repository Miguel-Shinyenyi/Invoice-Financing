// The tools the MCP server (mcp-server/) offers an agent. A test in mcp-server (frontendSync.test.ts) fails if this
// list and the server's registered tools differ, in names or in read/write kind.

export interface McpTool {
  name: string;
  kind: "read" | "write";
  does: string;
  /** Where the data comes from. */
  reads: string;
}

export const MCP_TOOLS: McpTool[] = [
  { name: "get_system_status", kind: "read", does: "Lab health, timers, caps, flow counters, personas", reads: "/lab/status, /lab/health, /lab/system, /lab/personas" },
  { name: "get_settlement", kind: "read", does: "One settlement with ledger entries, mismatches and what its status means", reads: "/lab/settlements/{id}/inspect, /lab/state-machines" },
  { name: "list_settlements", kind: "read", does: "Settlements from the read model, filtered by status", reads: "GET /settlements as the SUPPORT persona" },
  { name: "get_account", kind: "read", does: "Balance and ledger check (the engine may record a ledger mismatch)", reads: "GET /accounts/{id} as the SUPPORT persona" },
  { name: "list_account_settlements", kind: "read", does: "An account's settlements", reads: "GET /accounts/{id}/settlements as the SUPPORT persona" },
  { name: "list_open_reconciliation_mismatches", kind: "read", does: "Open settlement mismatches with a derived kind", reads: "/lab/reconciliation/mismatches" },
  { name: "list_ledger_mismatches", kind: "read", does: "Accounts whose stored balance disagrees with their entries", reads: "/lab/reconciliation/ledger-mismatches" },
  { name: "get_reconciliation_summary", kind: "read", does: "Open counts, recent runs, stranded UNKNOWN settlements", reads: "/lab/reconciliation/summary, /stranded, /runs" },
  { name: "get_invoice", kind: "read", does: "An invoice and its advance", reads: "GET /invoices/{id} as the SUPPORT persona" },
  { name: "explain_state_machine", kind: "read", does: "Settlement, invoice and advance states and transitions", reads: "/lab/state-machines" },
  { name: "create_settlement", kind: "write", does: "Move an amount between two accounts, idempotent on a key the agent supplies", reads: "POST /settlements as the ADMIN persona" },
  { name: "run_reconciliation", kind: "write", does: "Trigger one reconciliation run", reads: "POST /reconciliation/runs as the ADMIN persona" },
  { name: "resolve_settlement_mismatch", kind: "write", does: "Mark a mismatch RESOLVED with a reason (changes no data, Known gap 2)", reads: "POST /reconciliation/mismatches/{id}/resolve as the ADMIN persona" },
];
