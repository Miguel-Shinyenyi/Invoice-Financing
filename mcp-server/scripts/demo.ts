import { randomUUID } from "node:crypto";
import { resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

// A small real MCP client. It starts the server over stdio and calls every tool once, printing a
// trimmed result. Run `npm run demo`, or `npm run demo -- --writes` to include the write tools.
const root = resolve(import.meta.dirname, "..");
const writes = process.argv.includes("--writes");
const SHOW = 420;

const transport = new StdioClientTransport({
  command: resolve(root, "node_modules", ".bin", "tsx"),
  args: ["src/index.ts"],
  cwd: root,
  env: { ...(process.env as Record<string, string>), MCP_ALLOW_WRITES: writes ? "true" : "false" },
  stderr: "ignore",
});
const client = new Client({ name: "demo", version: "0" });
await client.connect(transport);

type Result = { isError?: boolean; content: { text: string }[]; structuredContent?: Record<string, any> };

async function call(name: string, args: Record<string, unknown> = {}): Promise<Result> {
  const r = (await client.callTool({ name, arguments: args })) as Result;
  const [summary = "", json = ""] = (r.content[0]?.text ?? "").split("\n");
  console.log(`\n### ${name} ${JSON.stringify(args)}${r.isError ? "  [error]" : ""}`);
  console.log(summary);
  if (json) console.log(json.length > SHOW ? `${json.slice(0, SHOW)} ... (${json.length} chars)` : json);
  return r;
}

const tools = (await client.listTools()).tools.map((t) => t.name);
console.log(`tools (${tools.length}): ${tools.join(", ")}`);

await call("get_system_status");
const summary = await call("get_reconciliation_summary");
await call("explain_state_machine", { machine: "settlement" });
const mismatches = await call("list_open_reconciliation_mismatches");
await call("list_ledger_mismatches");
const strandedId = summary.structuredContent?.strandedUnknown?.[0]?.settlementId as string | undefined;
if (strandedId) await call("get_settlement", { settlementId: strandedId });
const mismatchSettlement = mismatches.structuredContent?.items?.[0]?.settlementId as string | undefined;
if (mismatchSettlement) await call("get_settlement", { settlementId: mismatchSettlement });
await call("list_settlements", { limit: 3 });
const status = await call("get_system_status");
const owned = (status.structuredContent?.personas as { role: string; ownedAccounts: string[] }[] | undefined)?.find((p) => p.role === "READ_ONLY")?.ownedAccounts?.[0];
if (owned) {
  await call("get_account", { accountId: owned });
  await call("list_account_settlements", { accountId: owned, limit: 3 });
}
await call("get_account", { accountId: "50000000-0000-0000-0000-000000000002" });
await call("get_invoice", { invoiceId: "80000000-0000-0000-0000-000000000002" });

if (writes) {
  const key = randomUUID();
  const args = { sourceAccountId: "20000000-0000-0000-0000-000000000001", destinationAccountId: "20000000-0000-0000-0000-000000000002", amount: "10.00", currency: "USD", idempotencyKey: key };
  console.log(`\n--- create_settlement twice with one idempotency key (${key})`);
  const a = await call("create_settlement", args);
  const b = await call("create_settlement", args);
  console.log(`\nsame settlement id both times: ${a.structuredContent?.settlementId === b.structuredContent?.settlementId} (${String(a.structuredContent?.settlementId)})`);
  await call("run_reconciliation");
}

await client.close();
