"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Inspector } from "@/components/lab/Inspector";
import { Trace, Waterfall } from "@/components/lab/Waterfall";
import { Btn, Card, CodeBox, Field, Notice, PageHeader, Table } from "@/components/ui";
import { lab, type LabResult } from "@/lib/lab/client";
import { fmtDateTime, fmtTime, shortId } from "@/lib/lab/format";
import type { LogEvent } from "@/lib/lab/types";

interface Correlation {
  requestId: string;
  request: { method: string; path: string; status: number; startedAt: string; endedAt: string; durationMs: number; actor: string | null } | null;
  logs: LogEvent[];
  settlementIds: string[];
  invoiceIds: string[];
  traces: { available: boolean; traceIds: string[]; items: Trace[]; error: string | null };
  audit: Array<{ id: string; action: string; target_table: string; outcome: string; created_at: string; matchedBy: string }>;
  outbox: Array<{ id: string; topic: string; created_at: string; published_at: string | null; matchedBy: string }>;
  inspector: unknown | null;
}
interface RecentRequest { requestId: string; method: string; path: string; status: number; actor: string | null }

export default function CorrelatePage() {
  const params = useSearchParams();
  const [requestId, setRequestId] = useState(params.get("requestId") ?? "");
  const [result, setResult] = useState<LabResult<Correlation> | null>(null);
  const [recent, setRecent] = useState<RecentRequest[]>([]);
  const [busy, setBusy] = useState(false);

  async function load(id: string) {
    setBusy(true);
    setResult(await lab.get<Correlation>("correlate", { requestId: id }));
    setBusy(false);
  }

  useEffect(() => {
    let live = true;
    lab.get<RecentRequest[]>("requests", { limit: 25 }).then((r) => live && r.ok && r.data && setRecent(r.data));
    const initial = params.get("requestId");
    if (initial) lab.get<Correlation>("correlate", { requestId: initial }).then((r) => live && setResult(r));
    return () => {
      live = false;
    };
  }, [params]);

  const c = result?.data;
  return (
    <div className="space-y-5">
      <PageHeader title="Correlate" subtitle="Paste or click an X-Request-Id. One screen shows both services' log lines, the trace, the audit rows, the settlement inspector and the outbox events for that request: the proof that the pieces connect." />
      <Card title="Request id">
        <form className="flex flex-wrap gap-2" onSubmit={(e) => { e.preventDefault(); if (requestId) void load(requestId); }}>
          <input aria-label="Request id" className="neo-input min-w-0 flex-1 font-mono text-xs" value={requestId} onChange={(e) => setRequestId(e.target.value.trim())} placeholder="X-Request-Id" />
          <Btn type="submit" tone="yellow" busy={busy}>Correlate</Btn>
        </form>
        <div className="mt-3">
          <Field label="Or pick a recent request">
            {(id) => (
              <select id={id} className="neo-input w-full" value="" onChange={(e) => { setRequestId(e.target.value); if (e.target.value) void load(e.target.value); }}>
                <option value="">Recent requests…</option>
                {recent.map((r) => <option key={r.requestId} value={r.requestId}>{r.method} {r.path} → {r.status}{r.actor ? ` (${r.actor})` : ""} · {r.requestId}</option>)}
              </select>
            )}
          </Field>
        </div>
      </Card>

      {result && !result.ok && <Notice tone="red">{result.message}</Notice>}
      {c && (
        <>
          <Card title="The request">
            {c.request ? (
              <p className="text-sm font-semibold">{c.request.method} {c.request.path} → HTTP {c.request.status} in {c.request.durationMs} ms{c.request.actor ? ` as ${c.request.actor}` : ""} <span className="font-mono text-xs font-medium">({fmtDateTime(c.request.startedAt)})</span></p>
            ) : <Notice tone="yellow">This request id is not in the request index (it is bounded, and /lab reads are not indexed). Only its log lines are shown.</Notice>}
          </Card>
          <Card title={`Log lines, both services (${c.logs.length})`}>
            <CodeBox maxHeight="16rem">{c.logs.length === 0 ? "No log lines carry this request id." : c.logs.map((l) => `${fmtTime(l.timestamp)} ${l.service === "ml-service" ? "[ml]" : "[be]"} ${l.level.padEnd(5)} ${l.logger.split(".").pop()}: ${l.message}`).join("\n")}</CodeBox>
          </Card>
          <Card title="Trace">
            {!c.traces.available && <Notice tone="yellow">{c.traces.error ?? "Jaeger is not reachable."}</Notice>}
            {c.traces.available && c.traces.items.length === 0 && <p className="text-sm font-medium">No trace id on this request&apos;s log lines yet. The agent&apos;s trace id appears on every log line written inside a span.</p>}
            {c.traces.items.map((t) => <Waterfall key={t.traceId} trace={t} />)}
          </Card>
          <Card title={`Audit rows (${c.audit.length})`}>
            <Table head={["When", "Action", "Entity", "Outcome", "Matched by"]} empty={c.audit.length === 0 ? "None for this request." : undefined}>
              {c.audit.map((a) => (<tr key={a.id}><td className="px-3 py-1.5 font-mono text-xs">{fmtTime(a.created_at)}</td><td className="px-3 py-1.5 font-mono text-xs">{a.action}</td><td className="px-3 py-1.5 text-xs">{a.target_table}</td><td className="px-3 py-1.5">{a.outcome}</td><td className="px-3 py-1.5 text-xs">{a.matchedBy}</td></tr>))}
            </Table>
          </Card>
          <Card title={`Outbox events (${c.outbox.length})`}>
            <Table head={["Topic", "Created", "Published", "Matched by"]} empty={c.outbox.length === 0 ? "None for this request." : undefined}>
              {c.outbox.map((o) => (<tr key={o.id}><td className="px-3 py-1.5 font-mono text-xs">{o.topic}</td><td className="px-3 py-1.5 font-mono text-xs">{fmtTime(o.created_at)}</td><td className="px-3 py-1.5 font-mono text-xs">{o.published_at ? fmtTime(o.published_at) : "not yet"}</td><td className="px-3 py-1.5 text-xs">{o.matchedBy}</td></tr>))}
            </Table>
          </Card>
          {c.settlementIds.map((id) => <Inspector key={id} settlementId={id} />)}
          {c.invoiceIds.length > 0 && <p className="text-sm font-medium">Invoice {c.invoiceIds.map((i) => shortId(i)).join(", ")}: see <Link className="underline font-semibold" href="/lab/invoices">Invoices</Link>.</p>}
        </>
      )}
    </div>
  );
}
