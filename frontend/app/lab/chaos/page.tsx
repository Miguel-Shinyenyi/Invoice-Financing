"use client";

import Link from "next/link";
import { useEffect, useState, type ReactNode } from "react";
import { AccountSelect } from "@/components/lab/AccountSelect";
import { Inspector } from "@/components/lab/Inspector";
import { ShowRequest } from "@/components/lab/ShowRequest";
import { StatusBadge } from "@/components/StatusBadge";
import { Btn, Card, Field, KnownGap, Notice, PageHeader } from "@/components/ui";
import { choosePersona, lab, type LabResult } from "@/lib/lab/client";
import { POOL_1, POOL_2 } from "@/lib/lab/seedLabels";
import type { PlaygroundResponse } from "@/lib/lab/types";

function ChaosCard({ id, title, boundary, children, next }: { id: string; title: string; boundary: string; children: ReactNode; next: ReactNode }) {
  return (
    <Card id={id} title={title} right={<span className="neo-badge bg-white">boundary: {boundary}</span>}>
      {children}
      <div className="mt-3 border-t-2 border-black pt-2 text-xs font-medium"><strong className="font-black uppercase">What to look at next. </strong>{next}</div>
    </Card>
  );
}

function SettleFault({ mode, slowMs }: { mode: string; slowMs?: number }) {
  const [res, setRes] = useState<LabResult<PlaygroundResponse> | null>(null);
  const [busy, setBusy] = useState(false);
  async function go() {
    setBusy(true);
    setRes(await lab.post<PlaygroundResponse>("settlements", {
      idempotencyKey: crypto.randomUUID(), sourceAccountId: POOL_1, destinationAccountId: POOL_2, amount: 5, currency: "USD",
      fault: { mode, slowMs },
    }));
    setBusy(false);
  }
  const d = res?.data;
  return (
    <div className="space-y-2">
      <Btn tone="yellow" busy={busy} onClick={go}>Run: pool 1 → pool 2, 5.00 USD</Btn>
      {res && !res.ok && <Notice tone="red">{res.message}</Notice>}
      {d && (
        <div className="space-y-2 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            {d.result && <StatusBadge status={d.result.status} />}
            <span className="font-mono text-xs">externalRef {d.result?.externalRef ?? "null"}</span>
          </div>
          {d.stranded && <Notice tone="orange" title="Stranded.">{d.strandedExplanation} <KnownGap n={1} anchor="open-questions" /></Notice>}
          {d.orphanedExternalRef && <Notice tone="pink">The external system holds <code className="break-all font-mono">{d.orphanedExternalRef}</code>; the engine never learned it.</Notice>}
          {d.settlementId && <Inspector settlementId={d.settlementId} />}
        </div>
      )}
      <ShowRequest sent={res?.sent ?? null} />
    </div>
  );
}

function Orphan() {
  const [res, setRes] = useState<LabResult<{ settlementId: string; idempotencyKey: string; sweepEligibleAt: string; explanation: string }> | null>(null);
  const [swept, setSwept] = useState(0);
  const [busy, setBusy] = useState(false);
  async function go() {
    setBusy(true);
    setRes(await lab.post("settlements/orphan", { sourceAccountId: POOL_1, destinationAccountId: POOL_2, amount: 3, currency: "USD" }));
    setBusy(false);
  }
  async function sweep() {
    await lab.post("sweep/run");
    setSwept((n) => n + 1);
  }
  return (
    <div className="space-y-2">
      <Btn tone="yellow" busy={busy} onClick={go}>Leave a PENDING settlement behind</Btn>
      {res?.data && (
        <>
          <Notice tone="blue">{res.data.explanation}</Notice>
          <div className="flex flex-wrap gap-2">
            <Btn onClick={sweep}>Run the sweep now</Btn>
            <span className="self-center text-xs font-medium">Or wait: the scheduled sweep finalizes it after the grace period.</span>
          </div>
          <Inspector settlementId={res.data.settlementId} reloadKey={swept} />
        </>
      )}
      {res && !res.ok && <Notice tone="red">{res.message}</Notice>}
      <ShowRequest sent={res?.sent ?? null} />
    </div>
  );
}

function ExternalRef({ mode }: { mode: "forget" | "corrupt" }) {
  const [refs, setRefs] = useState<Array<{ ref: string; label: string }>>([]);
  const [ref, setRef] = useState("");
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState("");
  const [status, setStatus] = useState("");
  const [res, setRes] = useState<LabResult<unknown> | null>(null);
  const [run, setRun] = useState<LabResult<{ recordsChecked: number; mismatchesFound: number }> | null>(null);

  useEffect(() => {
    let live = true;
    lab.get<{ rows: Array<{ external_ref: string | null; status: string; amount: number }> }>("data/settlements", { columns: "external_ref,status,amount" }).then((r) => {
      if (!live || !r.ok || !r.data) return;
      const list = r.data.rows.filter((x) => x.external_ref && x.status === "CONFIRMED")
        .map((x) => ({ ref: x.external_ref as string, label: `${x.external_ref} · ${Number(x.amount)} · ${x.status}` }));
      setRefs(list);
      setRef((cur) => cur || list[0]?.ref || "");
    });
    return () => {
      live = false;
    };
  }, []);

  async function go() {
    const body = mode === "forget" ? undefined : {
      amount: amount ? Number(amount) : undefined, currency: currency || undefined, status: status || undefined,
    };
    setRes(await lab.post(`external/${ref}/${mode}`, body));
    setRun(null);
  }
  async function reconcile() {
    setRun(await lab.post("reconciliation/run"));
  }
  return (
    <div className="space-y-2">
      <div className="grid gap-2 sm:grid-cols-2">
        <Field label="Settlement's external reference">
          {(id) => <select id={id} className="neo-input w-full" value={ref} onChange={(e) => setRef(e.target.value)}>{refs.map((r) => <option key={r.ref} value={r.ref}>{r.label}</option>)}</select>}
        </Field>
        {mode === "corrupt" && (
          <>
            <Field label="Change amount to (optional)">{(id) => <input id={id} className="neo-input w-full" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="e.g. 99.00" />}</Field>
            <Field label="Change currency to (optional)">{(id) => <input id={id} className="neo-input w-full" maxLength={3} value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} placeholder="EUR" />}</Field>
            <Field label="Change status to (optional)">
              {(id) => <select id={id} className="neo-input w-full" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">(unchanged)</option><option>FAILED</option><option>CONFIRMED</option></select>}
            </Field>
          </>
        )}
      </div>
      <div className="flex flex-wrap gap-2">
        <Btn tone="yellow" disabled={!ref} onClick={go}>{mode === "forget" ? "Make the external system forget it" : "Corrupt the external record"}</Btn>
        <Btn disabled={!res?.ok} onClick={reconcile}>Run reconciliation now</Btn>
      </div>
      {res && !res.ok && <Notice tone="red">{res.message}</Notice>}
      {res?.ok && <Notice tone="green">Done. Seeded settlements are already past the grace period, so the next run flags it.</Notice>}
      {run?.data && <Notice tone="blue">Run checked {run.data.recordsChecked} settlements, {run.data.mismatchesFound} mismatch(es) open. <Link className="underline font-bold" href="/lab/reconciliation">See them</Link>.</Notice>}
      <ShowRequest sent={res?.sent ?? null} />
    </div>
  );
}

function HandEdit() {
  const [account, setAccount] = useState(POOL_1);
  const [delta, setDelta] = useState("40.00");
  const [edit, setEdit] = useState<LabResult<{ storedBalance: number; computedBalance: number }> | null>(null);
  const [read, setRead] = useState<LabResult<unknown> | null>(null);
  const [repair, setRepair] = useState<LabResult<unknown> | null>(null);

  async function doEdit() {
    setEdit(await lab.post("accounts/" + account + "/hand-edit-balance", { delta: Number(delta) }));
    setRead(null);
    setRepair(null);
  }
  async function doRead() {
    await choosePersona("ADMIN");
    setRead(await lab.realGet(`accounts/${account}`));
  }
  async function doRepair() {
    setRepair(await lab.post(`accounts/${account}/repair-balance`));
  }
  return (
    <div className="space-y-2">
      <div className="grid gap-2 sm:grid-cols-2">
        <Field label="Account">{(id) => <AccountSelect id={id} value={account} onChange={setAccount} />}</Field>
        <Field label="Add to the stored balance (±)">{(id) => <input id={id} className="neo-input w-full" inputMode="decimal" value={delta} onChange={(e) => setDelta(e.target.value)} />}</Field>
      </div>
      <div className="flex flex-wrap gap-2">
        <Btn tone="yellow" onClick={doEdit}>1. Edit the row by hand</Btn>
        <Btn disabled={!edit?.ok} onClick={doRead}>2. Read the account as ADMIN (real GET /accounts/{"{id}"})</Btn>
        <Btn tone="pink" disabled={!edit?.ok} onClick={doRepair} title="What a human editing the database would do. A lab-only helper, not a proposal for a product feature.">3. Repair: what a human editing the database would do</Btn>
      </div>
      {edit?.ok && edit.data && <Notice tone="blue">Stored balance is now {edit.data.storedBalance}; its entries net to {edit.data.computedBalance}.</Notice>}
      {edit && !edit.ok && <Notice tone="red">{edit.message}</Notice>}
      {read && (
        <Notice tone={read.status === 500 ? "red" : "green"} title={`HTTP ${read.status}:`}>
          {read.status === 500 ? "the ledger check refused to return a balance the entries do not back, and recorded a ledger mismatch." : "the stored balance matches its entries."}
          <pre className="mt-1 max-w-full overflow-auto font-mono text-xs">{JSON.stringify(read.data, null, 2)}</pre>
        </Notice>
      )}
      {repair?.ok && <Notice tone="green">Balance set back to the sum of its entries. The next read returns 200.</Notice>}
      <ShowRequest sent={read?.sent ?? edit?.sent ?? null} />
    </div>
  );
}

export default function ChaosPage() {
  return (
    <div className="space-y-5">
      <PageHeader title="Chaos" subtitle="One card per fault. Each runs the fault at a boundary, then walks you to the screen that shows its consequence." />
      <ChaosCard id="request-lost" title="Request lost (case 1)" boundary="gateway call"
        next={<>The settlement is UNKNOWN with no reference. <Link className="underline font-bold" href="/lab/reconciliation">Stranded UNKNOWN</Link> will list it, and it stays there.</>}>
        <SettleFault mode="REQUEST_LOST" />
      </ChaosCard>
      <ChaosCard id="response-lost" title="Response lost (case 2)" boundary="gateway call"
        next={<>The external store holds a record the engine cannot name. Check <Link className="underline font-bold" href="/lab/data">the data browser</Link> for the settlement, and the <Link className="underline font-bold" href="/lab/logs">logs</Link> for the gateway line.</>}>
        <SettleFault mode="RESPONSE_LOST" />
      </ChaosCard>
      <ChaosCard id="slow-gateway" title="Slow gateway" boundary="gateway call"
        next={<>Open <Link className="underline font-bold" href="/lab/traces">Traces</Link>: the gateway call is the long span. Or use the playground's “send twice at once” to get a 409 in progress.</>}>
        <SettleFault mode="SLOW" slowMs={2500} />
      </ChaosCard>
      <ChaosCard id="orphaned-pending" title="Orphaned PENDING (a crash between the two transactions)" boundary="crash point"
        next={<>The settlement turns UNKNOWN once the sweep runs. Retrying its key before that returns 409. Then see it on <Link className="underline font-bold" href="/lab/reconciliation">Stranded UNKNOWN</Link>.</>}>
        <Orphan />
      </ChaosCard>
      <ChaosCard id="external-forgotten" title="External record forgotten" boundary="external record store"
        next={<>Open <Link className="underline font-bold" href="/lab/reconciliation">Reconciliation</Link>: a “No external record found” mismatch. Then <Link className="underline font-bold" href="/lab/alerts">Alerts</Link>: no rule covers it (<KnownGap n={6} anchor="current-state" doc="docs/kafka-events.md" />).</>}>
        <ExternalRef mode="forget" />
      </ChaosCard>
      <ChaosCard id="external-corrupted" title="External record corrupted (amount, currency or status)" boundary="external record store"
        next={<>Different mismatch texts for amount/currency versus status. Resolve one and notice the settlement does not change (<KnownGap n={2} anchor="open-questions" />).</>}>
        <ExternalRef mode="corrupt" />
      </ChaosCard>
      <ChaosCard id="balance-hand-edit" title="Balance hand-edited" boundary="database row a human would edit"
        next={<>Open <Link className="underline font-bold" href="/lab/reconciliation">Reconciliation</Link>, resolve the ledger mismatch without repairing, then read the account again: a fresh row opens (<KnownGap n={2} anchor="open-questions" />).</>}>
        <HandEdit />
      </ChaosCard>
    </div>
  );
}
