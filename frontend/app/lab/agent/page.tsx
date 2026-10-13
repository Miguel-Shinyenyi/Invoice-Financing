"use client";

import { Card, CodeBox, DocLink, LiveBadge, Notice, PageHeader, Stat, Table } from "@/components/ui";
import { MCP_TOOLS } from "@/lib/lab/mcpTools";
import { useLabJson } from "@/lib/lab/useLabJson";

type Row = Record<string, string | number | null>;

interface Summary {
  openMismatches: number;
  openLedgerMismatches: number;
  strandedUnknown: number;
}

const SETUP = `# build once, from the repository root
cd mcp-server && npm ci && npm run build

# reads only (default)
claude mcp add settlement-engine-lab -e LAB_BASE_URL=http://127.0.0.1:8080 -- node $PWD/dist/index.js

# also register the three write tools
claude mcp add settlement-engine-lab -e LAB_BASE_URL=http://127.0.0.1:8080 -e MCP_ALLOW_WRITES=true -- node $PWD/dist/index.js`;

export default function AgentPage() {
  const { data: summary, mode } = useLabJson<Summary>("reconciliation/summary", undefined, 2000);
  const { data: open } = useLabJson<Row[]>("reconciliation/mismatches", { status: "OPEN" }, 2000);
  const { data: ledger } = useLabJson<Row[]>("reconciliation/ledger-mismatches", { status: "OPEN" }, 2000);
  const reads = MCP_TOOLS.filter((t) => t.kind === "read");
  const writes = MCP_TOOLS.filter((t) => t.kind === "write");

  return (
    <div className="space-y-5">
      <PageHeader
        title="Agent (MCP)"
        subtitle="An MCP server lets an AI agent use this sandbox through typed tools. The server runs on your machine over stdio, so this page does not run it or call it. It shows the tools, and the data an agent's read tools would see right now."
      />

      <Notice tone="yellow" title="This page is a view, not an agent.">
        Nothing here asks a model anything. The panels below read the same Lab views that the tools <code className="font-mono">get_reconciliation_summary</code>, <code className="font-mono">list_open_reconciliation_mismatches</code> and <code className="font-mono">list_ledger_mismatches</code> read. The server reshapes them (it adds a derived mismatch kind, plain-language summaries and result caps), so what an agent receives is not byte for byte what you see here. Design and decisions: <DocLink path="docs/mcp.md">mcp.md</DocLink>.
      </Notice>

      <Card title="What an agent's read tools see now" right={<LiveBadge mode={mode} />}>
        <div className="grid gap-3 sm:grid-cols-3">
          <Stat label="Open settlement mismatches" value={summary?.openMismatches ?? "…"} />
          <Stat label="Open ledger mismatches" value={summary?.openLedgerMismatches ?? "…"} />
          <Stat label="Stranded UNKNOWN" value={summary?.strandedUnknown ?? "…"} hint="never reconciled (Known gap 1)" />
        </div>
        <div className="mt-3 grid gap-3 lg:grid-cols-2">
          <div>
            <div className="mb-1 text-xs font-black">Open settlement mismatches (raw Lab view)</div>
            <CodeBox maxHeight="14rem">{JSON.stringify(open ?? [], null, 2)}</CodeBox>
          </div>
          <div>
            <div className="mb-1 text-xs font-black">Open ledger mismatches (raw Lab view)</div>
            <CodeBox maxHeight="14rem">{JSON.stringify(ledger ?? [], null, 2)}</CodeBox>
          </div>
        </div>
      </Card>

      <Card title={`Read tools (${reads.length}), always registered`}>
        <Table head={["Tool", "What it returns", "Where it reads"]}>
          {reads.map((t) => (
            <tr key={t.name} data-tool={t.name}>
              <td className="px-3 py-1.5 font-mono text-xs font-bold">{t.name}</td>
              <td className="px-3 py-1.5 text-xs">{t.does}</td>
              <td className="px-3 py-1.5 font-mono text-[11px]">{t.reads}</td>
            </tr>
          ))}
        </Table>
      </Card>

      <Card title={`Write tools (${writes.length}), only with MCP_ALLOW_WRITES=true`}>
        <Table head={["Tool", "What it does", "Call"]}>
          {writes.map((t) => (
            <tr key={t.name} data-tool={t.name}>
              <td className="px-3 py-1.5 font-mono text-xs font-bold">{t.name}</td>
              <td className="px-3 py-1.5 text-xs">{t.does}</td>
              <td className="px-3 py-1.5 font-mono text-[11px]">{t.reads}</td>
            </tr>
          ))}
        </Table>
        <p className="mt-2 text-xs font-medium">
          The flag defaults to off, and the tools are not even listed without it. They are also dropped if the Lab reports read-only mode. Writes change this sandbox for everyone until the next reset.
        </p>
      </Card>

      <Card title="Safety rules the server enforces">
        <ol className="list-decimal space-y-1 pl-5 text-xs font-medium">
          <li>It calls <code className="font-mono">GET /lab/status</code> at startup and refuses any target that is not a Lab sandbox.</li>
          <li>It refuses the shared staging host before sending a request. The list comes from the backend config, checked by a test.</li>
          <li>Reads use the SUPPORT persona (or READ_ONLY), writes use ADMIN. A read token is only ever sent on a GET. Tokens stay in memory.</li>
          <li>Write tools are not registered unless the flag is set.</li>
          <li>Lists default to 20 rows and stop at 100. Long results are cut, and the cut is reported.</li>
          <li>On a 429 or 503 it waits as the Lab asks, retries once, then returns a hint.</li>
          <li>Errors are short: no stack traces, tokens or raw upstream bodies.</li>
        </ol>
      </Card>

      <Card title="Connect an agent">
        <CodeBox>{SETUP}</CodeBox>
        <p className="mt-2 text-xs font-medium">Full reference: <code className="font-mono">mcp-server/README.md</code>. Evals and their results: <DocLink path="docs/mcp.md">mcp.md</DocLink>.</p>
      </Card>
    </div>
  );
}
