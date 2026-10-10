"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { AccountSelect } from "@/components/lab/AccountSelect";
import { Inspector } from "@/components/lab/Inspector";
import { ShowRequest } from "@/components/lab/ShowRequest";
import { StepList } from "@/components/lab/StepList";
import { StatusBadge } from "@/components/StatusBadge";
import { Btn, Card, DocLink, Field, KnownGap, Notice, PageHeader } from "@/components/ui";
import { lab, type LabResult } from "@/lib/lab/client";
import { POOL_1, POOL_2 } from "@/lib/lab/seedLabels";
import type { PlaygroundResponse } from "@/lib/lab/types";

const FAULTS = [
  ["NONE", "No fault: the gateway confirms"],
  ["REQUEST_LOST", "Request lost (case 1): never reached the external system"],
  ["RESPONSE_LOST", "Response lost (case 2): the external system did it, we never heard"],
  ["DECLINED", "Declined: the gateway answers FAILED"],
  ["SLOW", "Slow gateway: correct answer, late"],
] as const;

export default function PlaygroundPage() {
  const [key, setKey] = useState(() => crypto.randomUUID());
  const [source, setSource] = useState(POOL_1);
  const [destination, setDestination] = useState(POOL_2);
  const [amount, setAmount] = useState("12.50");
  const [fault, setFault] = useState<(typeof FAULTS)[number][0]>("NONE");
  const [slowMs, setSlowMs] = useState(1500);
  const [busy, setBusy] = useState(false);
  const [results, setResults] = useState<Array<LabResult<PlaygroundResponse>>>([]);
  const [inspectKey, setInspectKey] = useState(0);

  const body = (overrideAmount?: string, overrideFault?: string) => ({
    idempotencyKey: key,
    sourceAccountId: source,
    destinationAccountId: destination,
    amount: Number(overrideAmount ?? amount),
    currency: "USD",
    fault: { mode: overrideFault ?? fault, slowMs: (overrideFault ?? fault) === "SLOW" ? slowMs : undefined },
  });

  async function send(path: "settlements" | "settlements/retry", payload: object) {
    setBusy(true);
    const r = await lab.post<PlaygroundResponse>(path, payload);
    setResults([r]);
    setInspectKey((k) => k + 1);
    setBusy(false);
  }

  async function sendTwiceAtOnce() {
    setBusy(true);
    const payload = body(undefined, "SLOW");
    const [a, b] = await Promise.all([
      lab.post<PlaygroundResponse>("settlements", { ...payload, fault: { mode: "SLOW", slowMs: 2500 } }),
      new Promise<LabResult<PlaygroundResponse>>((res) =>
        setTimeout(() => lab.post<PlaygroundResponse>("settlements", { ...payload, fault: { mode: "NONE" } }).then(res), 400)),
    ]);
    setResults([a, b]);
    setInspectKey((k) => k + 1);
    setBusy(false);
  }

  const data = results[0]?.data;
  const inspectParam = useSearchParams().get("inspect");

  return (
    <div className="space-y-5">
      <PageHeader
        title="Playground"
        subtitle="Create a settlement through the real SettlementService, choose how the gateway misbehaves, then re-send the same key to see the cached response and the two 409 cases."
      />
      <Card title="Request">
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="Source account">{(id) => <AccountSelect id={id} value={source} onChange={setSource} />}</Field>
          <Field label="Destination account">{(id) => <AccountSelect id={id} value={destination} onChange={setDestination} />}</Field>
          <Field label="Amount (USD)">
            {(id) => <input id={id} className="neo-input w-full" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />}
          </Field>
          <Field label="Gateway fault">
            {(id) => (
              <select id={id} className="neo-input w-full" value={fault} onChange={(e) => setFault(e.target.value as typeof fault)}>
                {FAULTS.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
              </select>
            )}
          </Field>
          {fault === "SLOW" && (
            <Field label="Added latency (ms)" hint="Capped by the server at 3000.">
              {(id) => <input id={id} type="number" min={0} max={3000} className="neo-input w-full" value={slowMs} onChange={(e) => setSlowMs(Number(e.target.value))} />}
            </Field>
          )}
          <Field label="Idempotency-Key" hint="Generated once per logical request. Reuse it to retry.">
            {(id) => (
              <div className="flex gap-2">
                <input id={id} className="neo-input min-w-0 flex-1 font-mono text-xs" value={key} onChange={(e) => setKey(e.target.value)} />
                <Btn type="button" onClick={() => setKey(crypto.randomUUID())}>New key</Btn>
              </div>
            )}
          </Field>
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          <Btn tone="yellow" busy={busy} onClick={() => send("settlements", body())}>Create settlement</Btn>
          <Btn busy={busy} onClick={() => send("settlements/retry", body(undefined, "NONE"))}>Retry: same key, same body</Btn>
          <Btn busy={busy} onClick={() => send("settlements/retry", body(String(Number(amount) + 1), "NONE"))}>Retry: same key, different amount</Btn>
          <Btn busy={busy} onClick={sendTwiceAtOnce}>Send twice at once (409 in progress)</Btn>
        </div>
      </Card>

      {results.map((r, i) => (
        <Card key={i} title={results.length > 1 ? `Response ${i + 1}` : "Response"}>
          {!r.ok && <Notice tone="red">{r.message ?? "The sandbox refused this request."}</Notice>}
          {r.data && r.ok && (
            <div className="space-y-2 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <span className="neo-badge bg-surface">would be HTTP {r.data.httpStatus}</span>
                {r.data.result && <StatusBadge status={r.data.result.status} />}
                {r.data.replay && <span className="neo-badge tone-blue">cached response, replayed</span>}
                {r.data.fault !== "NONE" && <span className="neo-badge bg-surface">fault: {r.data.fault}</span>}
              </div>
              {r.data.error && <Notice tone="yellow">{r.data.error}</Notice>}
              {r.data.result && (
                <p className="break-all font-mono text-xs">settlement {r.data.result.settlementId} · {r.data.result.amount} {r.data.result.currency} · externalRef {r.data.result.externalRef ?? "null"}</p>
              )}
              {r.data.stranded && (
                <Notice tone="orange" title="Stranded.">
                  {r.data.strandedExplanation} <KnownGap n={1} anchor="open-questions" />
                </Notice>
              )}
              {r.data.orphanedExternalRef && (
                <Notice tone="pink" title="The external system holds a record the engine never learned the reference of:">
                  <code className="break-all font-mono">{r.data.orphanedExternalRef}</code>{" "}
                  ({r.data.externalRecordHeld ? "it is in the external store right now" : "no longer held"}). Money moved there, not here.
                </Notice>
              )}
              <div className="flex flex-wrap gap-3 text-xs">
                {r.data.requestId && <Link className="underline" href={`/lab/correlate?requestId=${r.data.requestId}`}>correlate this request</Link>}
                {r.data.settlementId && <Link className="underline" href={`/lab/logs?settlementId=${r.data.settlementId}`}>its logs</Link>}
                {r.data.settlementId && <Link className="underline" href={`/lab/traces?settlementId=${r.data.settlementId}`}>its traces</Link>}
                <DocLink path="docs/reconciliation.md" anchor="idempotency">how idempotency works</DocLink>
              </div>
            </div>
          )}
          <div className="mt-3"><ShowRequest sent={r.sent} /></div>
        </Card>
      ))}

      {data && <StepList steps={data.steps} />}
      {data?.settlementId && <Inspector settlementId={data.settlementId} reloadKey={inspectKey} />}
      {!data && inspectParam && /^[0-9a-f-]{36}$/i.test(inspectParam) && <Inspector settlementId={inspectParam} />}
    </div>
  );
}
