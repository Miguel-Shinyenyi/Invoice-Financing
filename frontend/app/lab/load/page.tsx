"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { LineChart, StackedBars } from "@/components/lab/charts";
import { ShowRequest } from "@/components/lab/ShowRequest";
import { StatusBadge } from "@/components/StatusBadge";
import { Btn, Card, CodeBox, DocLink, Field, KnownGap, LiveBadge, Notice, PageHeader, Stat, Table } from "@/components/ui";
import { BASE_PATH } from "@/lib/basePath";
import { lab, streamUrl, type LabResult } from "@/lib/lab/client";
import { fmtMs, fmtNum } from "@/lib/lab/format";
import type { LabStatus, LoadPlan, LoadRun, LoadSample } from "@/lib/lab/types";
import { useLabJson } from "@/lib/lab/useLabJson";
import { useLive } from "@/lib/lab/useLive";

const SCENARIOS: Array<[LoadPlan["scenario"], string]> = [
  ["FRESH_SETTLEMENTS", "Fresh settlements across the 6-account pool"],
  ["DUPLICATE_KEY_BURST", "Duplicate-key burst on the dedicated pair"],
  ["INVOICE_FINANCING", "Invoice submit, then finance"],
];

const FAULT_PRESETS: Array<[string, string, LoadPlan["fault"]]> = [
  ["none", "No faults", null],
  ["lost", "5% responses lost (UNKNOWN, stranded)", { requestLost: 0, responseLost: 0.05, declined: 0, slow: 0, slowMs: 0 }],
  ["declined", "70% declined (pushes the failure-rate alert)", { requestLost: 0, responseLost: 0, declined: 0.7, slow: 0, slowMs: 0 }],
  ["slow", "30% slow, 1.5 s", { requestLost: 0, responseLost: 0, declined: 0, slow: 0.3, slowMs: 1500 }],
];

function deltas(samples: LoadSample[], pick: (s: LoadSample) => Record<string, number>, keys: string[]): Array<{ name: string; values: number[] }> {
  return keys.map((k) => ({
    name: k,
    values: samples.map((s, i) => Math.max(0, (pick(s)[k] ?? 0) - (i === 0 ? 0 : pick(samples[i - 1])[k] ?? 0))),
  }));
}

function Compare({ current, previous }: { current: LoadRun; previous: LoadRun }) {
  const rows: Array<[string, number | null, number | null, string]> = [
    ["Requests", current.summary.totalRequests, previous.summary.totalRequests, ""],
    ["Avg requests/s", current.summary.averageRequestsPerSecond, previous.summary.averageRequestsPerSecond, ""],
    ["p50 ms", current.summary.p50Ms, previous.summary.p50Ms, "lower is better"],
    ["p95 ms", current.summary.p95Ms, previous.summary.p95Ms, "lower is better"],
    ["p99 ms", current.summary.p99Ms, previous.summary.p99Ms, "lower is better"],
    ["Finalize retries", current.summary.finalizeRetries, previous.summary.finalizeRetries, "lower is better"],
    ["UNKNOWN fallbacks", current.summary.unknownFallbacks, previous.summary.unknownFallbacks, "lower is better"],
    ["Peak Hikari pending", current.summary.peakHikariPending, previous.summary.peakHikariPending, "lower is better"],
  ];
  return (
    <Card title={`Compared with the previous run (${previous.plan.scenario}, ${previous.plan.virtualUsers} users)`}>
      <Table head={["Metric", "This run", "Previous", "Change"]}>
        {rows.map(([label, a, b, hint]) => (
          <tr key={label}>
            <td className="px-3 py-1.5 font-bold">{label} {hint && <span className="text-[10px] font-medium opacity-60">({hint})</span>}</td>
            <td className="px-3 py-1.5 font-mono">{a === null ? "—" : fmtNum(a, 1)}</td>
            <td className="px-3 py-1.5 font-mono">{b === null ? "—" : fmtNum(b, 1)}</td>
            <td className="px-3 py-1.5 font-mono">{a === null || b === null ? "—" : `${a - b >= 0 ? "+" : ""}${fmtNum(a - b, 1)}`}</td>
          </tr>
        ))}
      </Table>
    </Card>
  );
}

export default function LoadPage() {
  const { data: status } = useLabJson<LabStatus>("status", undefined, 5000);
  const caps = status?.caps;

  const [scenario, setScenario] = useState<LoadPlan["scenario"]>("FRESH_SETTLEMENTS");
  const [users, setUsers] = useState(5);
  const [seconds, setSeconds] = useState(10);
  const [total, setTotal] = useState(500);
  const [amount, setAmount] = useState("1.00");
  const [preset, setPreset] = useState("none");
  const [busy, setBusy] = useState(false);
  const [startResult, setStartResult] = useState<LabResult<LoadRun> | null>(null);
  const [run, setRun] = useState<LoadRun | null>(null);
  const [samples, setSamples] = useState<LoadSample[]>([]);
  const [history, setHistory] = useState<LoadRun[]>([]);
  const running = run?.status === "RUNNING";

  const loadHistory = useCallback(async () => {
    const r = await lab.get<LoadRun[]>("load/history");
    if (r.ok && r.data) setHistory(r.data);
  }, []);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadHistory();
  }, [loadHistory]);

  const runId = run?.runId ?? null;
  const pollRun = useCallback(async () => {
    if (!runId) return;
    const r = await lab.get<LoadRun>(`load/${runId}`);
    if (r.ok && r.data) {
      setRun(r.data);
      setSamples(r.data.samples);
      if (r.data.status !== "RUNNING") void loadHistory();
    }
  }, [runId, loadHistory]);

  const stream = useMemo(
    () => runId ? {
      url: streamUrl(`load/${runId}/stream`),
      events: ["sample", "done"],
      onEvent: (name: string, data: string) => {
        try {
          if (name === "sample") setSamples((prev) => [...prev, JSON.parse(data) as LoadSample]);
          if (name === "done") {
            const final = JSON.parse(data) as LoadRun;
            setRun(final);
            setSamples(final.samples);
            void loadHistory();
          }
        } catch {
          /* a malformed event is ignored; the next poll corrects the view */
        }
      },
    } : null,
    [runId, loadHistory],
  );
  const mode = useLive({ enabled: running, poll: pollRun, stream });

  async function start() {
    setBusy(true);
    setSamples([]);
    const fault = FAULT_PRESETS.find((p) => p[0] === preset)?.[2] ?? null;
    const plan: Omit<LoadPlan, "seed"> & { seed: number } = {
      scenario, virtualUsers: users, durationSeconds: seconds, totalRequests: total, amount: Number(amount), fault, seed: Date.now() % 100000,
    };
    const r = await lab.post<LoadRun>("load/start", plan);
    setStartResult(r);
    if (r.ok && r.data) setRun(r.data);
    setBusy(false);
  }

  async function cancel() {
    if (run) await lab.post(`load/${run.runId}/cancel`);
  }

  const view = samples;
  const previous = history.find((h) => h.runId !== run?.runId && h.status !== "RUNNING") ?? null;
  const statusKeys = [...new Set(view.flatMap((s) => Object.keys(s.statusCounts)))].sort();
  const outcomeKeys = [...new Set(view.flatMap((s) => Object.keys(s.outcomeCounts)))].sort();
  const last = view[view.length - 1];
  const tooBig = !!caps && (users > caps.maxVirtualUsers || seconds > caps.maxDurationSeconds || total > caps.maxTotalRequests);

  return (
    <div className="space-y-5">
      <PageHeader
        title="Load"
        subtitle="Real HTTP requests to the sandbox backend on loopback, with the sandbox ADMIN token, so the security chain, request-id filter, audit logging and JSON serialization are all in the measured path. Every number is measured, none is estimated."
      />
      <Card title="Plan" right={caps && <span className="text-xs font-bold">caps from the server: ≤{caps.maxVirtualUsers} users, ≤{caps.maxDurationSeconds}s, ≤{caps.maxTotalRequests} requests</span>}>
        <div className="grid gap-3 md:grid-cols-3">
          <Field label="Scenario">
            {(id) => <select id={id} className="neo-input w-full" value={scenario} onChange={(e) => setScenario(e.target.value as LoadPlan["scenario"])}>{SCENARIOS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select>}
          </Field>
          <Field label={`Virtual users (1–${caps?.maxVirtualUsers ?? "…"})`}>
            {(id) => <input id={id} type="number" min={1} max={caps?.maxVirtualUsers} className="neo-input w-full" value={users} onChange={(e) => setUsers(Number(e.target.value))} />}
          </Field>
          <Field label={`Duration, seconds (1–${caps?.maxDurationSeconds ?? "…"})`}>
            {(id) => <input id={id} type="number" min={1} max={caps?.maxDurationSeconds} className="neo-input w-full" value={seconds} onChange={(e) => setSeconds(Number(e.target.value))} />}
          </Field>
          <Field label={`Total requests (1–${caps?.maxTotalRequests ?? "…"})`}>
            {(id) => <input id={id} type="number" min={1} max={caps?.maxTotalRequests} className="neo-input w-full" value={total} onChange={(e) => setTotal(Number(e.target.value))} />}
          </Field>
          <Field label={`Amount (${caps?.minAmount ?? "…"}–${caps?.maxAmount ?? "…"})`}>
            {(id) => <input id={id} className="neo-input w-full" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />}
          </Field>
          <Field label="Fault profile" hint="Applied at the gateway boundary for this run only.">
            {(id) => <select id={id} className="neo-input w-full" value={preset} onChange={(e) => setPreset(e.target.value)}>{FAULT_PRESETS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select>}
          </Field>
        </div>
        {tooBig && <div className="mt-3"><Notice tone="yellow">One of the values is above the server's cap; the server will refuse it.</Notice></div>}
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <Btn tone="yellow" busy={busy || running} onClick={start}>{running ? "Running…" : "Start run"}</Btn>
          {running && <Btn tone="red" onClick={cancel}>Cancel</Btn>}
          <LiveBadge mode={running ? mode : "paused"} />
        </div>
        {startResult && !startResult.ok && <div className="mt-3"><Notice tone="red">{startResult.message ?? "The run was refused."}</Notice></div>}
        <div className="mt-3"><ShowRequest sent={startResult?.sent ?? null} /></div>
      </Card>

      {run && (
        <>
          <Card title={`Run ${run.runId.slice(0, 8)}…`} right={<StatusBadge status={run.status === "COMPLETED" ? "PASS" : run.status === "RUNNING" ? "PENDING" : "FAIL"} />}>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              <Stat label="Requests" value={fmtNum(last?.totalRequests ?? run.summary.totalRequests)} />
              <Stat label="Requests/s (now)" value={fmtNum(last?.requestsPerSecond, 1)} />
              <Stat label="p50" value={fmtMs(last?.p50Ms ?? run.summary.p50Ms)} />
              <Stat label="p95" value={fmtMs(last?.p95Ms ?? run.summary.p95Ms)} />
              <Stat label="p99" value={fmtMs(last?.p99Ms ?? run.summary.p99Ms)} />
              <Stat label="Finalize retries" value={fmtNum(last?.finalizeRetries ?? 0)} hint="settlement.finalize.retries" />
              <Stat label="UNKNOWN fallbacks" value={fmtNum(last?.unknownFallbacks ?? 0)} hint="settlement.unknown.fallback" />
              <Stat label="Hikari active / pending" value={`${last?.hikariActive ?? 0} / ${last?.hikariPending ?? 0}`} />
            </div>
          </Card>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card><LineChart title="Throughput" unit="requests / second" series={[{ name: "requests/s", values: view.map((s) => s.requestsPerSecond) }]} /></Card>
            <Card><LineChart title="Latency percentiles" unit="ms" series={[
              { name: "p50", values: view.map((s) => s.p50Ms) }, { name: "p95", values: view.map((s) => s.p95Ms) }, { name: "p99", values: view.map((s) => s.p99Ms) }]} /></Card>
            <Card><StackedBars title="HTTP status per second" series={deltas(view, (s) => s.statusCounts, statusKeys)} /></Card>
            <Card><StackedBars title="Settlement outcome per second" series={deltas(view, (s) => s.outcomeCounts, outcomeKeys)} /></Card>
            <Card><LineChart title="Retries and fallbacks (cumulative)" series={[
              { name: "finalize retries", values: view.map((s) => s.finalizeRetries) }, { name: "UNKNOWN fallbacks", values: view.map((s) => s.unknownFallbacks) }]} /></Card>
            <Card><LineChart title="Hikari connection pool" unit="connections" series={[
              { name: "active", values: view.map((s) => s.hikariActive) }, { name: "pending", values: view.map((s) => s.hikariPending) }, { name: "idle", values: view.map((s) => s.hikariIdle) }]} /></Card>
          </div>

          {run.status !== "RUNNING" && (
            <Card title="Invariants checked against the database" right={<StatusBadge status={run.verdict === "PASS" ? "PASS" : run.verdict === "FAIL" ? "FAIL" : "SKIPPED"} />}>
              <ul className="space-y-2">
                {run.invariants.map((i) => (
                  <li key={i.id} data-invariant={i.id} data-status={i.status} className="grid grid-cols-[5.5rem_1fr] items-start gap-2 border-2 border-black p-2">
                    <StatusBadge status={i.status} />
                    <div className="min-w-0"><div className="text-sm font-black">{i.title}</div><div className="break-words text-xs font-medium">{i.detail}</div></div>
                  </li>
                ))}
              </ul>
              {run.stranded && (
                <div className="mt-3">
                  <Notice tone="orange" title={`${run.stranded.unknownOutcomes} settlement(s) ended UNKNOWN.`}>
                    {run.stranded.explanation} <KnownGap n={1} anchor="open-questions" />
                  </Notice>
                </div>
              )}
              <div className="mt-3 flex flex-wrap items-center gap-3 text-xs">
                <a className="neo-btn" href={`${BASE_PATH}/api/lab/load/${run.runId}?download=true`}>Download JSON</a>
                <DocLink path="load/README.md" anchor="the-lab-runner-versus-k6">Lab runner versus k6: which to quote</DocLink>
              </div>
              <p className="mt-3 text-xs font-bold uppercase">The same plan with k6</p>
              <CodeBox maxHeight="7rem">{run.k6Equivalent}</CodeBox>
              <p className="mt-2 text-xs font-medium">{run.note}</p>
            </Card>
          )}
          {run.status !== "RUNNING" && previous && <Compare current={run} previous={previous} />}
        </>
      )}

      <Card title="Last runs (kept in the sandbox database; a reset clears them)">
        <Table head={["Started", "Scenario", "Users", "Requests", "p95", "Verdict"]} empty={history.length === 0 ? "No runs yet." : undefined}>
          {history.map((h) => (
            <tr key={h.runId}>
              <td className="px-3 py-1.5 font-mono text-xs">{new Date(h.startedAt).toLocaleTimeString("en-GB")}</td>
              <td className="px-3 py-1.5 text-xs font-bold">{h.plan.scenario}</td>
              <td className="px-3 py-1.5 font-mono">{h.plan.virtualUsers}</td>
              <td className="px-3 py-1.5 font-mono">{h.summary.totalRequests}</td>
              <td className="px-3 py-1.5 font-mono">{fmtMs(h.summary.p95Ms)}</td>
              <td className="px-3 py-1.5"><StatusBadge status={h.verdict === "PASS" ? "PASS" : h.verdict === "FAIL" ? "FAIL" : "SKIPPED"} /></td>
            </tr>
          ))}
        </Table>
      </Card>
    </div>
  );
}
