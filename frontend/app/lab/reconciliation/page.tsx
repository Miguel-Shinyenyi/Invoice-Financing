"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { PersonaPicker, usePersona } from "@/components/lab/PersonaPicker";
import { useNow } from "@/components/lab/ResetPanel";
import { ShowRequest } from "@/components/lab/ShowRequest";
import { StatusBadge } from "@/components/StatusBadge";
import { Btn, Card, DocLink, KnownGap, LiveBadge, Notice, PageHeader, Stat, Table } from "@/components/ui";
import { lab, type LabResult } from "@/lib/lab/client";
import { countdown, fmtDateTime, shortId } from "@/lib/lab/format";
import type { LabStatus } from "@/lib/lab/types";
import { useLabJson } from "@/lib/lab/useLabJson";

type Row = Record<string, string | number | null>;

interface Summary {
  openMismatches: number;
  openLedgerMismatches: number;
  unseenMismatches: number;
  strandedUnknown: number;
  note: string;
}

function ResolveForm({ kind, id, role, onDone }: { kind: "mismatches" | "ledger-mismatches"; id: string; role: string | null; onDone: (r: LabResult<unknown>) => void }) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <form
      className="flex flex-wrap gap-2"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        onDone(await lab.realPost(`reconciliation/${kind}/${id}/resolve`, { reason: reason || "reviewed in the Lab" }));
        setBusy(false);
      }}
    >
      <input aria-label="Resolution reason" className="neo-input min-w-0 flex-1" placeholder="reason (required by the endpoint)" value={reason} onChange={(e) => setReason(e.target.value)} />
      <Btn type="submit" busy={busy} disabled={!role}>Resolve</Btn>
    </form>
  );
}

export default function ReconciliationPage() {
  const persona = usePersona();
  const { data: status } = useLabJson<LabStatus>("status", undefined, 3000);
  const { data: summary, mode } = useLabJson<Summary>("reconciliation/summary", undefined, 1000);
  const { data: runs } = useLabJson<Row[]>("reconciliation/runs", { limit: 8 }, 2000);
  const { data: open } = useLabJson<Row[]>("reconciliation/mismatches", { status: "OPEN" }, 1000);
  const { data: resolved } = useLabJson<Row[]>("reconciliation/mismatches", { status: "RESOLVED" }, 3000);
  const { data: lOpen } = useLabJson<Row[]>("reconciliation/ledger-mismatches", { status: "OPEN" }, 1000);
  const { data: lResolved } = useLabJson<Row[]>("reconciliation/ledger-mismatches", { status: "RESOLVED" }, 3000);
  const { data: stranded } = useLabJson<{ settlements: Row[]; explanation: string }>("reconciliation/stranded", undefined, 2000);
  const now = useNow();

  const [arrivedWithUnseen, setArrivedWithUnseen] = useState<number | null>(null);
  const markedSeen = useRef(false);
  useEffect(() => {
    if (!summary || markedSeen.current) return;
    markedSeen.current = true;
    setArrivedWithUnseen(summary.unseenMismatches);
    const t = setTimeout(() => void lab.post("reconciliation/seen"), 1500); // opening this screen is what counts as seeing
    return () => clearTimeout(t);
  }, [summary]);

  const [runResult, setRunResult] = useState<LabResult<{ recordsChecked: number; mismatchesFound: number }> | null>(null);
  const [lastResolve, setLastResolve] = useState<LabResult<unknown> | null>(null);
  const [reread, setReread] = useState<LabResult<unknown> | null>(null);
  const [resolvedAccount, setResolvedAccount] = useState<string | null>(null);

  const runNow = useCallback(async () => setRunResult(await lab.post("reconciliation/run")), []);
  const reopen = useCallback(async (accountId: string) => setReread(await lab.realGet(`accounts/${accountId}`)), []);

  const resolveDone = (r: LabResult<unknown>, accountId?: string) => {
    setLastResolve(r);
    if (accountId && r.ok) setResolvedAccount(accountId);
  };

  return (
    <div className="space-y-5">
      <PageHeader title="Reconciliation" subtitle="The scheduled comparison of settlements with the external system's records, and the ledger-consistency mismatches found on account reads." >
        <LiveBadge mode={mode} />
      </PageHeader>

      <Card title="Who is resolving">
        <PersonaPicker role={persona.role} onChoose={persona.choose} />
        <p className="mt-2 text-xs font-medium">Resolve forms call the real <code className="font-mono">/reconciliation/**</code> endpoints with this persona's JWT. READ_ONLY gets a genuine 403.</p>
      </Card>

      <Notice tone="orange" title="Resolving does not change data.">
        Resolving records a reason and an audit entry. It does not change the settlement or the balance. For a ledger mismatch, the next read of the account opens a fresh row until someone fixes the data by hand, and no endpoint corrects data.{" "}
        <KnownGap n={2} anchor="open-questions" />
      </Notice>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Next scheduled run">
          <div className="grid grid-cols-2 gap-2">
            <Stat label="Estimated in" value={now ? countdown(status?.next.reconciliationEstimate, now) : "—"} hint={`every ${(status?.timeCompression.reconciliationIntervalMs ?? 0) / 1000}s after the last run finishes`} />
            <div className="flex items-end"><Btn tone="yellow" className="w-full" onClick={runNow}>Run now</Btn></div>
          </div>
          {runResult?.ok && runResult.data && <div className="mt-2"><Notice tone="green">Checked {runResult.data.recordsChecked}, {runResult.data.mismatchesFound} open mismatch(es).</Notice></div>}
          <div className="mt-2"><ShowRequest sent={runResult?.sent ?? null} /></div>
        </Card>
        <Card title="Is anyone being told?" right={<KnownGap n={6} anchor="current-state" doc="docs/kafka-events.md" />}>
          <div className="grid grid-cols-3 gap-2">
            <Stat label="Unseen mismatches" value={summary?.unseenMismatches ?? "…"} hint="opened since anyone looked" tone={summary && summary.unseenMismatches > 0 ? "orange" : "default"} />
            <Stat label="Open (settlement)" value={summary?.openMismatches ?? "…"} />
            <Stat label="Open (ledger)" value={summary?.openLedgerMismatches ?? "…"} />
          </div>
          {arrivedWithUnseen !== null && <p className="mt-2 text-xs font-bold">When you opened this screen, {arrivedWithUnseen} had gone unseen.</p>}
          <p className="mt-1 text-xs font-medium">{summary?.note} See the <Link className="underline font-bold" href="/lab/alerts">alert board</Link> and the zero-consumer topic on <Link className="underline font-bold" href="/lab/events">Events</Link>.</p>
        </Card>
      </div>

      <Card title="Open settlement mismatches">
        <Table head={["Settlement", "Internal", "External", "Details", "Opened", "Resolve"]} empty={open && open.length === 0 ? "None open." : undefined}>
          {(open ?? []).map((m) => (
            <tr key={String(m.id)}>
              <td className="px-3 py-1.5 font-mono text-xs"><Link className="underline" href={`/lab/data?table=settlements`}>{shortId(String(m.settlementId))}</Link></td>
              <td className="px-3 py-1.5"><StatusBadge status={String(m.internalState)} /></td>
              <td className="px-3 py-1.5">{m.externalState ? <StatusBadge status={String(m.externalState)} /> : "no record"}</td>
              <td className="px-3 py-1.5 text-xs">{m.details}</td>
              <td className="px-3 py-1.5 font-mono text-xs">{fmtDateTime(String(m.createdAt))}</td>
              <td className="min-w-64 px-3 py-1.5"><ResolveForm kind="mismatches" id={String(m.id)} role={persona.role} onDone={(r) => resolveDone(r)} /></td>
            </tr>
          ))}
        </Table>
        <details className="mt-3"><summary className="cursor-pointer text-xs font-black uppercase">Resolved ({resolved?.length ?? 0})</summary>
          <div className="mt-2"><Table head={["Settlement", "Details", "Resolved"]}>
            {(resolved ?? []).map((m) => (<tr key={String(m.id)}><td className="px-3 py-1.5 font-mono text-xs">{shortId(String(m.settlementId))}</td><td className="px-3 py-1.5 text-xs">{m.details}</td><td className="px-3 py-1.5 font-mono text-xs">{fmtDateTime(String(m.resolvedAt))}</td></tr>))}
          </Table></div>
        </details>
      </Card>

      <Card title="Ledger mismatches (the screen the dashboard was missing)" right={<KnownGap n={4} anchor="ledger-consistency-mismatches">closed by this rewrite</KnownGap>}>
        <Table head={["Account", "Stored", "Computed", "Opened", "Resolve"]} empty={lOpen && lOpen.length === 0 ? "None open." : undefined}>
          {(lOpen ?? []).map((m) => (
            <tr key={String(m.id)}>
              <td className="px-3 py-1.5 font-mono text-xs">{shortId(String(m.accountId))}</td>
              <td className="px-3 py-1.5 font-mono">{m.storedBalance}</td>
              <td className="px-3 py-1.5 font-mono">{m.computedBalance}</td>
              <td className="px-3 py-1.5 font-mono text-xs">{fmtDateTime(String(m.createdAt))}</td>
              <td className="min-w-64 px-3 py-1.5"><ResolveForm kind="ledger-mismatches" id={String(m.id)} role={persona.role} onDone={(r) => resolveDone(r, String(m.accountId))} /></td>
            </tr>
          ))}
        </Table>
        {lastResolve && (
          <div className="mt-3">
            <Notice tone={lastResolve.ok ? "green" : "red"} title={`HTTP ${lastResolve.status}:`}>
              {lastResolve.ok ? "the row is RESOLVED. The balance was not touched." : (lastResolve.message ?? "refused.")}
            </Notice>
            <ShowRequest sent={lastResolve.sent} />
          </div>
        )}
        {resolvedAccount && (
          <div className="mt-3 border-2 border-black p-3">
            <p className="text-sm font-bold">Now watch the loop: read that account again.</p>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <Btn tone="yellow" onClick={() => reopen(resolvedAccount)}>GET /accounts/{shortId(resolvedAccount)} as {persona.role ?? "…"}</Btn>
              {reread && <span className="text-sm font-black">HTTP {reread.status}: {reread.status === 500 ? "a fresh mismatch row has opened above." : "consistent."}</span>}
            </div>
            <p className="mt-1 text-xs font-medium">To close the loop, repair the data by hand on the <Link className="underline font-bold" href="/lab/chaos#balance-hand-edit">Chaos page</Link>: it stands in for a human editing the database.</p>
          </div>
        )}
        <details className="mt-3"><summary className="cursor-pointer text-xs font-black uppercase">Resolved ({lResolved?.length ?? 0})</summary>
          <div className="mt-2"><Table head={["Account", "Details", "Resolved"]}>
            {(lResolved ?? []).map((m) => (<tr key={String(m.id)}><td className="px-3 py-1.5 font-mono text-xs">{shortId(String(m.accountId))}</td><td className="px-3 py-1.5 text-xs">{m.details}</td><td className="px-3 py-1.5 font-mono text-xs">{fmtDateTime(String(m.resolvedAt))}</td></tr>))}
          </Table></div>
        </details>
      </Card>

      <Card title="Stranded UNKNOWN (no external reference)" right={<KnownGap n={1} anchor="open-questions" />}>
        <p className="mb-2 text-xs font-medium">{stranded?.explanation}</p>
        <Table head={["Settlement", "Amount", "Updated"]} empty={stranded && stranded.settlements.length === 0 ? "None." : undefined}>
          {(stranded?.settlements ?? []).map((s) => (
            <tr key={String(s.settlementId)}>
              <td className="px-3 py-1.5 font-mono text-xs"><Link className="underline" href={`/lab/playground?inspect=${s.settlementId}`}>{shortId(String(s.settlementId))}</Link></td>
              <td className="px-3 py-1.5 font-mono">{s.amount} {s.currency}</td>
              <td className="px-3 py-1.5 font-mono text-xs">{fmtDateTime(String(s.updatedAt))}</td>
            </tr>
          ))}
        </Table>
      </Card>

      <Card title="Run history">
        <Table head={["Started", "Finished", "Checked", "Mismatches", "Status"]}>
          {(runs ?? []).map((r) => (
            <tr key={String(r.id)}>
              <td className="px-3 py-1.5 font-mono text-xs">{fmtDateTime(String(r.startedAt))}</td>
              <td className="px-3 py-1.5 font-mono text-xs">{fmtDateTime(r.finishedAt ? String(r.finishedAt) : null)}</td>
              <td className="px-3 py-1.5 font-mono">{r.recordsChecked}</td>
              <td className="px-3 py-1.5 font-mono">{r.mismatchesFound}</td>
              <td className="px-3 py-1.5"><StatusBadge status={r.status === "COMPLETED" ? "RESOLVED" : String(r.status)} /></td>
            </tr>
          ))}
        </Table>
        <p className="mt-2 text-xs"><DocLink path="docs/reconciliation.md" anchor="reconciliation-process">How a run decides</DocLink></p>
      </Card>
    </div>
  );
}
