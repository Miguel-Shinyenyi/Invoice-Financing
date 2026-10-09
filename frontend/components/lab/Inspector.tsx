"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Card, CodeBox, Notice, Table } from "@/components/ui";
import { StatusBadge } from "@/components/StatusBadge";
import { lab } from "@/lib/lab/client";
import { fmtDateTime, fmtTime, shortId } from "@/lib/lab/format";
import type { Inspection } from "@/lib/lab/types";

const s = (v: unknown) => (v === null || v === undefined ? "—" : String(v));

/** Read-only: the settlement row, its idempotency key, ledger entries, outbox events, mismatch rows, audit rows and log events. */
export function Inspector({ settlementId, reloadKey = 0 }: { settlementId: string; reloadKey?: number }) {
  const [data, setData] = useState<Inspection | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    lab.get<Inspection>(`settlements/${settlementId}/inspect`).then((r) => {
      if (!live) return;
      if (r.ok && r.data) {
        setData(r.data);
        setError(null);
      } else setError(r.message ?? "Could not inspect this settlement.");
    });
    return () => {
      live = false;
    };
  }, [settlementId, reloadKey]);

  if (error) return <Notice tone="red">{error}</Notice>;
  if (!data) return <Card title="Inspector">Loading…</Card>;
  const st = data.settlement;
  const key = data.idempotencyKey;

  return (
    <Card title={`Inspector · settlement ${shortId(settlementId)}`} className="space-y-4">
      <div className="grid gap-2 text-sm sm:grid-cols-2">
        <div className="border-2 border-black p-2">
          <div className="text-xs font-black uppercase">Settlement row</div>
          <div className="mt-1 flex flex-wrap items-center gap-2"><StatusBadge status={s(st.status)} /><span className="font-mono">{s(st.amount)} {s(st.currency)}</span></div>
          <div className="mt-1 break-all font-mono text-xs">externalRef: {s(st.external_ref)}</div>
          <div className="font-mono text-xs">created {fmtDateTime(s(st.created_at))}</div>
          <div className="font-mono text-xs">updated {fmtDateTime(s(st.updated_at))}</div>
        </div>
        <div className="border-2 border-black p-2">
          <div className="text-xs font-black uppercase">Idempotency key</div>
          {key ? (
            <>
              <div className="mt-1"><span className="neo-badge bg-white">{s(key.status)}</span></div>
              <div className="mt-1 break-all font-mono text-xs">{s(key.key)}</div>
            </>
          ) : <p>—</p>}
          <div className="mt-1 text-xs font-medium">
            External system: {data.external.recordHeld ? "holds a record" : "holds no record"}
            {data.external.orphanedExternalRef && <> · reference the engine never learned: <code className="font-mono">{data.external.orphanedExternalRef}</code></>}
          </div>
        </div>
      </div>

      <section>
        <h3 className="mb-1 text-xs font-black uppercase">Ledger entries ({data.ledgerEntries.length})</h3>
        <Table head={["Type", "Amount", "Account", "Created"]} empty={data.ledgerEntries.length === 0 ? "None: no money moved." : undefined}>
          {data.ledgerEntries.map((e) => (
            <tr key={s(e.id)}><td className="px-3 py-1.5 font-bold">{s(e.entry_type)}</td><td className="px-3 py-1.5 font-mono">{s(e.amount)}</td><td className="px-3 py-1.5 font-mono">{shortId(s(e.account_id))}</td><td className="px-3 py-1.5 font-mono">{fmtTime(s(e.created_at))}</td></tr>
          ))}
        </Table>
      </section>

      <section>
        <h3 className="mb-1 text-xs font-black uppercase">Outbox events ({data.outboxEvents.length})</h3>
        <Table head={["Topic", "Created", "Published"]} empty={data.outboxEvents.length === 0 ? "None." : undefined}>
          {data.outboxEvents.map((e) => (
            <tr key={s(e.id)}><td className="px-3 py-1.5 font-mono">{s(e.topic)}</td><td className="px-3 py-1.5 font-mono">{fmtTime(s(e.created_at))}</td><td className="px-3 py-1.5 font-mono">{e.published_at ? fmtTime(s(e.published_at)) : "not yet"}</td></tr>
          ))}
        </Table>
      </section>

      <section>
        <h3 className="mb-1 text-xs font-black uppercase">Mismatch rows ({data.mismatches.length} settlement, {data.ledgerMismatches.length} ledger)</h3>
        {data.mismatches.length + data.ledgerMismatches.length === 0 ? <p className="text-sm font-medium">None.</p> : (
          <Table head={["Kind", "Status", "Details"]}>
            {data.mismatches.map((m) => (<tr key={s(m.id)}><td className="px-3 py-1.5">settlement</td><td className="px-3 py-1.5"><StatusBadge status={s(m.resolution_status)} /></td><td className="px-3 py-1.5 text-xs">{s(m.details)}</td></tr>))}
            {data.ledgerMismatches.map((m) => (<tr key={s(m.id)}><td className="px-3 py-1.5">ledger</td><td className="px-3 py-1.5"><StatusBadge status={s(m.resolution_status)} /></td><td className="px-3 py-1.5 text-xs">{s(m.details)}</td></tr>))}
          </Table>
        )}
      </section>

      <section>
        <h3 className="mb-1 text-xs font-black uppercase">Audit rows ({data.audit.length})</h3>
        {data.audit.length === 0 ? <p className="text-sm font-medium">None for this settlement.</p> : (
          <Table head={["Action", "Outcome", "At"]}>
            {data.audit.map((a) => (<tr key={s(a.id)}><td className="px-3 py-1.5 font-mono">{s(a.action)}</td><td className="px-3 py-1.5">{s(a.outcome)}</td><td className="px-3 py-1.5 font-mono">{fmtTime(s(a.created_at))}</td></tr>))}
          </Table>
        )}
      </section>

      <section>
        <h3 className="mb-1 flex flex-wrap items-center gap-3 text-xs font-black uppercase">
          Log events ({data.logs.length})
          <Link className="underline" href={`/lab/logs?settlementId=${settlementId}`}>open in logs</Link>
          {data.traceIds.map((t) => (<Link key={t} className="underline" href={`/lab/traces?traceId=${t}`}>trace {t.slice(0, 8)}…</Link>))}
          {data.traceIds.length === 0 && <span className="font-medium normal-case opacity-70">no trace id on these lines</span>}
        </h3>
        <CodeBox maxHeight="12rem">
          {data.logs.length === 0 ? "No log lines." : data.logs.map((l) => `${fmtTime(l.timestamp)} ${l.level.padEnd(5)} ${l.logger.split(".").pop()}: ${l.message}`).join("\n")}
        </CodeBox>
      </section>
    </Card>
  );
}
