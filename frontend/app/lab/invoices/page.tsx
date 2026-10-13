"use client";

import Link from "next/link";
import { useState } from "react";
import { ShowRequest } from "@/components/lab/ShowRequest";
import { StatusBadge } from "@/components/StatusBadge";
import { Btn, Card, DocLink, Field, Notice, PageHeader, Table } from "@/components/ui";
import { lab, type LabResult } from "@/lib/lab/client";
import { fmtMoney, shortId } from "@/lib/lab/format";
import { useLabJson } from "@/lib/lab/useLabJson";

interface ScenarioInfo { id: string; title: string; description: string }
interface ScenarioResult {
  scenario: string;
  title: string;
  description: string;
  invoiceId: string;
  httpStatus: number;
  error: string | null;
  assessment: { score: number; decision: string; reasons: string[] } | null;
  inputsSent: Record<string, number>;
  advance: { amountAdvanced: number; fee: number; status: string; disbursedSettlementId: string } | null;
  invoiceStatus: string;
  setup: string[];
  requestId: string | null;
}
type Row = Record<string, string | number | null>;

export default function InvoicesLab() {
  const { data: scenarios } = useLabJson<ScenarioInfo[]>("invoices/scenarios", undefined, 60000);
  const { data: invoices, refresh } = useLabJson<{ rows: Row[] }>("data/invoices", undefined, 2000);
  const { data: advances } = useLabJson<{ rows: Row[] }>("data/advances", undefined, 2000);
  const [busy, setBusy] = useState<string | null>(null);
  const [res, setRes] = useState<LabResult<ScenarioResult> | null>(null);
  const [payInvoice, setPayInvoice] = useState("");
  const [paidAmount, setPaidAmount] = useState("");
  const [paidResult, setPaidResult] = useState<LabResult<unknown> | null>(null);
  const [repay, setRepay] = useState<LabResult<unknown> | null>(null);

  async function run(id: string) {
    setBusy(id);
    setRes(await lab.post<ScenarioResult>("invoices/demo", { scenario: id }));
    setBusy(null);
    await refresh();
  }

  const d = res?.data;
  const invoiceId = d?.invoiceId;
  const advanceFor = (id: string) => advances?.rows.find((a) => a.invoice_id === id);

  return (
    <div className="space-y-5">
      <PageHeader title="Invoices and fraud" subtitle="Each scenario creates real invoices and runs POST /invoices/{id}/finance through the real InvoiceService and the real fraud service. The score, reasons and decision are shown exactly as the service returned them." />

      <Card title="Scenarios">
        <div className="grid gap-3 md:grid-cols-2">
          {(scenarios ?? []).map((s) => (
            <div key={s.id} className="rounded-xl bg-surface-2 p-3">
              <div className="text-sm font-semibold">{s.title}</div>
              <p className="my-1 text-xs font-medium">{s.description}</p>
              <Btn tone="yellow" busy={busy === s.id} onClick={() => run(s.id)}>Run this scenario</Btn>
            </div>
          ))}
        </div>
      </Card>

      {res && !res.ok && <Notice tone="red">{res.message}</Notice>}
      {d && (
        <Card title={d.title}>
          <div className="space-y-3 text-sm">
            {d.setup.length > 0 && (<div><div className="text-xs font-semibold">Set-up the scenario did first</div><ul className="list-disc pl-5 text-xs font-medium">{d.setup.map((s) => <li key={s}>{s}</li>)}</ul></div>)}
            <div>
              <div className="text-xs font-semibold">What the engine sent to POST /score</div>
              <div className="mt-1 grid grid-cols-2 gap-2 sm:grid-cols-4">
                {Object.entries(d.inputsSent).map(([k, v]) => (<div key={k} className="rounded-xl bg-surface-2 p-2"><div className="break-all font-mono text-[10px]">{k}</div><div className="font-mono text-base font-semibold">{String(v)}</div></div>))}
              </div>
            </div>
            {d.assessment ? (
              <div className="rounded-xl bg-surface-2 p-3">
                <div className="flex flex-wrap items-center gap-3">
                  <StatusBadge status={d.assessment.decision} />
                  <span className="font-mono text-2xl font-semibold" data-testid="fraud-score">{Number(d.assessment.score).toFixed(3)}</span>
                  <span className="text-xs font-medium">score (BLOCK at 0.7 or above)</span>
                </div>
                <div className="mt-2 h-4 rounded-xl bg-surface-2" role="img" aria-label={`score ${d.assessment.score} of 1, block threshold 0.7`}>
                  <div className="relative h-full" style={{ width: `${Math.min(100, d.assessment.score * 100)}%`, background: d.assessment.decision === "BLOCK" ? "var(--chart-err)" : "var(--chart-3)" }} />
                </div>
                <p className="mt-2 text-xs font-semibold">Reasons: {d.assessment.reasons.length ? d.assessment.reasons.join(", ") : "none"}</p>
              </div>
            ) : <Notice tone="yellow">No assessment was stored.</Notice>}
            {d.httpStatus === 422 && <Notice tone="red" title="Blocked.">{d.error} Nothing was disbursed; the invoice is still {d.invoiceStatus}.</Notice>}
            {d.advance && (<Notice tone="green" title={`Advance ${d.advance.status}:`}>{fmtMoney(d.advance.amountAdvanced)} advanced, {fmtMoney(d.advance.fee)} fee, settlement {shortId(d.advance.disbursedSettlementId)}. Invoice is {d.invoiceStatus}.</Notice>)}
            <div className="flex flex-wrap gap-3 text-xs">
              {d.requestId && <Link className="underline" href={`/lab/correlate?requestId=${d.requestId}`}>correlate this request</Link>}
              <Link className="underline" href={`/lab/traces?invoiceId=${d.invoiceId}`}>its traces (the outbound POST /score span)</Link>
              <DocLink path="docs/fraud-detection.md" anchor="fail-open">fail-open behaviour</DocLink>
            </div>
          </div>
          <div className="mt-3"><ShowRequest sent={res?.sent ?? null} /></div>
        </Card>
      )}

      <Card title="Advance lifecycle: customer pays, repayment runs">
        <div className="grid gap-3 md:grid-cols-2">
          <div>
            <Field label="Mark an invoice paid" hint="Leave the amount empty to pay in full. A different amount is left for review by the repayment job.">
              {(id) => (
                <select id={id} className="neo-input w-full" value={payInvoice || invoiceId || ""} onChange={(e) => setPayInvoice(e.target.value)}>
                  <option value="">Pick a FINANCED or OVERDUE invoice…</option>
                  {(invoices?.rows ?? []).filter((i) => i.status === "FINANCED" || i.status === "OVERDUE").map((i) => (<option key={String(i.id)} value={String(i.id)}>{i.customer_reference} · {i.amount} · {i.status}</option>))}
                </select>
              )}
            </Field>
            <input aria-label="Paid amount" className="neo-input mt-2 w-full" inputMode="decimal" placeholder="paid amount (optional)" value={paidAmount} onChange={(e) => setPaidAmount(e.target.value)} />
            <Btn className="mt-2" onClick={async () => {
              const id = payInvoice || invoiceId;
              if (id) setPaidResult(await lab.post(`invoices/${id}/mark-paid`, paidAmount ? { amount: Number(paidAmount) } : undefined));
            }}>Mark paid</Btn>
            {paidResult && <div className="mt-2"><Notice tone={paidResult.ok ? "green" : "red"}>{paidResult.ok ? "Recorded in the mock payment source (in memory: a restart or reset forgets it)." : paidResult.message}</Notice></div>}
          </div>
          <div>
            <div className="text-xs font-semibold">Repayment job</div>
            <p className="my-1 text-xs font-medium">Collects advance plus fee from the business to the platform for every paid invoice. It also runs by itself every few seconds in the sandbox.</p>
            <Btn onClick={async () => { setRepay(await lab.post("invoices/repayment-run")); await refresh(); }}>Run repayment now</Btn>
            {repay?.ok && <div className="mt-2"><Notice tone="green">Repayment run completed.</Notice></div>}
          </div>
        </div>
        <div className="mt-4">
          <Table head={["Invoice", "Customer reference", "Amount", "Invoice", "Advance", "Fee"]} empty={(invoices?.rows.length ?? 0) === 0 ? "No invoices." : undefined}>
            {(invoices?.rows ?? []).map((i) => {
              const a = advanceFor(String(i.id));
              return (
                <tr key={String(i.id)}>
                  <td className="px-3 py-1.5 font-mono text-xs">{shortId(String(i.id))}</td>
                  <td className="px-3 py-1.5 text-xs">{i.customer_reference}</td>
                  <td className="px-3 py-1.5 font-mono">{i.amount}</td>
                  <td className="px-3 py-1.5"><StatusBadge status={String(i.status)} /></td>
                  <td className="px-3 py-1.5">{a ? <StatusBadge status={String(a.status)} /> : "—"}</td>
                  <td className="px-3 py-1.5 font-mono">{a ? String(a.fee) : "—"}</td>
                </tr>
              );
            })}
          </Table>
        </div>
        <p className="mt-2 text-xs font-medium">Observation: repayment needs the business to hold advance plus fee, but a financing only credits the advance. New lab businesses start with a small opening balance for that reason.</p>
      </Card>
    </div>
  );
}
