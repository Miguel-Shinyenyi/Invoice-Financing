"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ShowRequest } from "@/components/lab/ShowRequest";
import { Trace, Waterfall } from "@/components/lab/Waterfall";
import { Btn, Card, Field, Notice, PageHeader, Table } from "@/components/ui";
import { lab, type LabResult } from "@/lib/lab/client";
import { fmtMicros } from "@/lib/lab/format";

interface Summary { traceId: string; rootOperation: string; services: string[]; spanCount: number; durationMicros: number; hasError: boolean }

export default function TracesPage() {
  const params = useSearchParams();
  const [form, setForm] = useState({ requestId: params.get("requestId") ?? "", settlementId: params.get("settlementId") ?? "", invoiceId: params.get("invoiceId") ?? "" });
  const [list, setList] = useState<LabResult<Summary[]> | null>(null);
  const [trace, setTrace] = useState<LabResult<Trace> | null>(null);
  const [busy, setBusy] = useState(false);

  async function search(f = form) {
    setBusy(true);
    setList(await lab.get<Summary[]>("traces", { ...f, limit: 10 }));
    setBusy(false);
  }
  async function open(id: string) {
    setTrace(await lab.get<Trace>(`traces/${id}`));
  }

  useEffect(() => {
    const id = params.get("traceId");
    if (id) {
      let live = true;
      lab.get<Trace>(`traces/${id}`).then((r) => live && setTrace(r));
      return () => {
        live = false;
      };
    }
    let live = true;
    lab.get<Summary[]>("traces", {
      requestId: params.get("requestId"), settlementId: params.get("settlementId"), invoiceId: params.get("invoiceId"), limit: 10,
    }).then((r) => live && setList(r));
    return () => {
      live = false;
    };
  }, [params]);

  return (
    <div className="space-y-5">
      <PageHeader title="Traces" subtitle="Distributed traces from the OpenTelemetry agent, read from the sandbox's Jaeger. Find a trace by request id, settlement id or invoice id (resolved through the trace ids on the log lines), or browse the most recent." />
      <Card title="Find">
        <form className="grid gap-2 sm:grid-cols-3" onSubmit={(e) => { e.preventDefault(); void search(); }}>
          <Field label="Request id">{(id) => <input id={id} className="neo-input w-full font-mono text-xs" value={form.requestId} onChange={(e) => setForm({ ...form, requestId: e.target.value })} />}</Field>
          <Field label="Settlement id">{(id) => <input id={id} className="neo-input w-full font-mono text-xs" value={form.settlementId} onChange={(e) => setForm({ ...form, settlementId: e.target.value })} />}</Field>
          <Field label="Invoice id">{(id) => <input id={id} className="neo-input w-full font-mono text-xs" value={form.invoiceId} onChange={(e) => setForm({ ...form, invoiceId: e.target.value })} />}</Field>
          <div className="sm:col-span-3 flex gap-2">
            <Btn type="submit" tone="yellow" busy={busy}>Search</Btn>
            <Btn type="button" onClick={() => { const empty = { requestId: "", settlementId: "", invoiceId: "" }; setForm(empty); void search(empty); }}>Most recent</Btn>
          </div>
        </form>
        <div className="mt-2"><ShowRequest sent={list?.sent ?? null} /></div>
      </Card>

      {list && !list.ok && <Notice tone="red">{list.message}</Notice>}
      {list?.data && (
        <Card title={`Traces (${list.data.length})`}>
          <Table head={["Root operation", "Services", "Spans", "Duration", ""]} empty={list.data.length === 0 ? "No traces matched. Traces appear a few seconds after a request; a filter finds only requests whose log lines carry a trace id." : undefined}>
            {list.data.map((t) => (
              <tr key={t.traceId} data-trace={t.traceId}>
                <td className="px-3 py-1.5 text-xs font-bold">{t.hasError && <span aria-label="has an error span">✗ </span>}{t.rootOperation}<div className="font-mono text-[10px] font-medium">{t.traceId}</div></td>
                <td className="px-3 py-1.5 text-xs">{t.services.join(", ")}</td>
                <td className="px-3 py-1.5 font-mono">{t.spanCount}</td>
                <td className="px-3 py-1.5 font-mono">{fmtMicros(t.durationMicros)}</td>
                <td className="px-3 py-1.5"><Btn onClick={() => open(t.traceId)}>Open</Btn></td>
              </tr>
            ))}
          </Table>
        </Card>
      )}

      {trace && !trace.ok && <Notice tone="red">{trace.message}</Notice>}
      {trace?.data && (
        <Card title="Span waterfall" right={<Link className="text-xs font-bold underline" href="/lab/logs">logs</Link>}>
          <Waterfall trace={trace.data} />
        </Card>
      )}
    </div>
  );
}
