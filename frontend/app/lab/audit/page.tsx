"use client";

import { useState } from "react";
import { Btn, Card, Field, LiveBadge, PageHeader, Table } from "@/components/ui";
import { fmtDateTime, shortId } from "@/lib/lab/format";
import { useLabJson } from "@/lib/lab/useLabJson";

interface AuditRow { id: string; action: string; entity: string | null; entityId: string | null; outcome: string; actor: string; at: string }
interface AuditView { rows: AuditRow[]; actions: string[] }

export default function AuditPage() {
  const [actor, setActor] = useState("");
  const [action, setAction] = useState("");
  const [limit, setLimit] = useState(50);
  const { data, mode } = useLabJson<AuditView>("audit", { actor, action, limit }, 2000);
  return (
    <div className="space-y-5">
      <PageHeader title="Audit log" subtitle="Who did what, from the audit_log table. User ids are shown as the persona's name.">
        <LiveBadge mode={mode} />
      </PageHeader>
      <Card title="Filters">
        <div className="grid gap-2 sm:grid-cols-3">
          <Field label="Who">{(id) => <select id={id} className="neo-input w-full" value={actor} onChange={(e) => setActor(e.target.value)}><option value="">everyone</option><option>lab-admin</option><option>lab-support</option><option>lab-readonly</option></select>}</Field>
          <Field label="Action">{(id) => <select id={id} className="neo-input w-full" value={action} onChange={(e) => setAction(e.target.value)}><option value="">all</option>{(data?.actions ?? []).map((a) => <option key={a}>{a}</option>)}</select>}</Field>
          <Field label="Rows">{(id) => <select id={id} className="neo-input w-full" value={limit} onChange={(e) => setLimit(Number(e.target.value))}><option>25</option><option>50</option><option>100</option><option>200</option></select>}</Field>
        </div>
        <div className="mt-2"><Btn onClick={() => { setActor(""); setAction(""); }}>Clear</Btn></div>
      </Card>
      <Card title={`Rows (${data?.rows.length ?? 0})`}>
        <Table head={["When", "Who", "Action", "Entity", "Outcome"]} empty={data && data.rows.length === 0 ? "No audit rows match." : undefined}>
          {(data?.rows ?? []).map((r) => (
            <tr key={r.id} data-action={r.action} data-actor={r.actor}>
              <td className="px-3 py-1.5 font-mono text-xs">{fmtDateTime(r.at)}</td>
              <td className="px-3 py-1.5 font-bold">{r.actor}</td>
              <td className="px-3 py-1.5 font-mono text-xs">{r.action}</td>
              <td className="px-3 py-1.5 font-mono text-xs">{r.entity} {shortId(r.entityId)}</td>
              <td className="px-3 py-1.5"><span className={`neo-badge ${r.outcome === "SUCCESS" ? "bg-[var(--color-neo-green)]" : "bg-[var(--color-neo-red)] text-white"}`}><span aria-hidden className="mr-1">{r.outcome === "SUCCESS" ? "✓" : "✗"}</span>{r.outcome}</span></td>
            </tr>
          ))}
        </Table>
      </Card>
    </div>
  );
}
