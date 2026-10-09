"use client";

import Link from "next/link";
import { useState } from "react";
import { ShowRequest } from "@/components/lab/ShowRequest";
import { StatusBadge } from "@/components/StatusBadge";
import { Btn, Card, CodeBox, KnownGap, LiveBadge, Notice, PageHeader, Table } from "@/components/ui";
import { lab, type LabResult } from "@/lib/lab/client";
import { fmtDateTime } from "@/lib/lab/format";
import { useLabJson } from "@/lib/lab/useLabJson";

interface Rule { name: string; state: string; expression: string | null; sandboxForSeconds: number | null; realForSeconds: number | null; note?: string }
interface Board { available: boolean; rules: Rule[]; alertmanager: Array<{ name: string; severity: string; summary: string; startsAt: string; state: string }>; note: string }

export default function AlertsPage() {
  const { data, mode } = useLabJson<Board>("alerts", undefined, 2000);
  const [seconds, setSeconds] = useState(45);
  const [fault, setFault] = useState<LabResult<{ seconds: number; expiresAt: string; effect: string }> | null>(null);
  const [busy, setBusy] = useState(false);

  return (
    <div className="space-y-5">
      <PageHeader title="Alerts" subtitle="The three real alert rules with their live state from the sandbox's Prometheus and Alertmanager, plus the one rule that does not exist.">
        <LiveBadge mode={mode} />
      </PageHeader>
      {data && !data.available && <Notice tone="yellow">Prometheus did not answer, so rule states are shown as unknown rather than guessed.</Notice>}
      <Notice tone="blue" title="Shorter durations here.">{data?.note ?? "The sandbox uses the same expressions as staging with shorter for: durations, so a rule can reach firing within a minute."}</Notice>

      <Card title="Alert board">
        <ul className="space-y-3">
          {(data?.rules ?? []).map((r) => (
            <li key={r.name} data-alert={r.name} data-state={r.state} className="border-2 border-black p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="text-sm font-black">{r.name}</div>
                {r.state === "no rule exists" ? <span className="neo-badge bg-[var(--color-neo-orange)]"><span aria-hidden className="mr-1">⚠</span>no rule exists</span> : <StatusBadge status={r.state} />}
              </div>
              {r.expression && <div className="mt-2"><CodeBox maxHeight="5rem">{r.expression}</CodeBox></div>}
              {r.sandboxForSeconds !== null && (
                <p className="mt-1 text-xs font-medium">for: <strong>{r.sandboxForSeconds}s</strong> in the sandbox · <strong>{r.realForSeconds ?? "?"}s</strong> on staging</p>
              )}
              {r.note && <p className="mt-1 text-xs font-medium">{r.note} <KnownGap n={6} anchor="current-state" doc="docs/kafka-events.md" /></p>}
            </li>
          ))}
        </ul>
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Trigger MLServiceDown with a real cause">
          <p className="mb-2 text-xs font-medium">Makes the fraud service answer 503 on /metrics and /score for a while, then recover by itself. Prometheus cannot scrape it, so the rule counts to firing, and the backend fails open.</p>
          <label className="block text-xs font-black uppercase" htmlFor="ml-seconds">Seconds (1–60)</label>
          <input id="ml-seconds" type="range" min={5} max={60} value={seconds} onChange={(e) => setSeconds(Number(e.target.value))} className="w-full" />
          <p className="font-mono text-sm font-black">{seconds}s</p>
          <Btn tone="red" busy={busy} onClick={async () => { setBusy(true); setFault(await lab.post("ml/fault", { seconds })); setBusy(false); }}>Take the fraud service down</Btn>
          {fault?.ok && fault.data && <div className="mt-2"><Notice tone="green">{fault.data.effect} Back at {fmtDateTime(fault.data.expiresAt)}.</Notice></div>}
          {fault && !fault.ok && <div className="mt-2"><Notice tone="yellow">{fault.message}</Notice></div>}
          <p className="mt-2 text-xs font-medium">While it is down, <Link className="underline font-bold" href="/lab/invoices">finance a clean invoice</Link>: it still succeeds with no fraud signal (fail-open).</p>
          <div className="mt-2"><ShowRequest sent={fault?.sent ?? null} /></div>
        </Card>
        <Card title="Trigger SettlementFailureRateSpike">
          <p className="mb-2 text-xs font-medium">Run a load plan with the “70% declined” fault profile. The gateway returns FAILED for most calls, so more than half of settlement outcomes are FAILED. The rule&apos;s 5-minute window needs a little time to move.</p>
          <Link className="neo-btn bg-[var(--color-neo-yellow)]" href="/lab/load">Open Load</Link>
          <h3 className="mb-1 mt-4 text-xs font-black uppercase">BackendDown</h3>
          <p className="text-xs font-medium">Fires only when the backend itself is unscrapeable, which would end the sandbox, so there is no button for it.</p>
        </Card>
      </div>

      <Card title="Alertmanager: active alerts">
        <Table head={["Alert", "Severity", "Since", "State", "Summary"]} empty={data && data.alertmanager.length === 0 ? "No active alerts." : undefined}>
          {(data?.alertmanager ?? []).map((a) => (
            <tr key={`${a.name}${a.startsAt}`}><td className="px-3 py-1.5 font-bold">{a.name}</td><td className="px-3 py-1.5">{a.severity}</td><td className="px-3 py-1.5 font-mono text-xs">{fmtDateTime(a.startsAt)}</td><td className="px-3 py-1.5">{a.state}</td><td className="px-3 py-1.5 text-xs">{a.summary}</td></tr>
          ))}
        </Table>
      </Card>
    </div>
  );
}
