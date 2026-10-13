"use client";

import { useCallback, useState } from "react";
import { BarList, Histogram, LineChart } from "@/components/lab/charts";
import { Card, CodeBox, Disclosure, LiveBadge, PageHeader, Stat, Table } from "@/components/ui";
import { lab } from "@/lib/lab/client";
import { fmtMs, fmtNum } from "@/lib/lab/format";
import { useLive } from "@/lib/lab/useLive";

interface Snapshot {
  now: string;
  settlementOutcome: Record<string, number>;
  counters: { finalizeRetries: number; unknownFallbacks: number };
  http: Record<string, { count: number; meanMs: number; maxMs: number; p50Ms?: number; p95Ms?: number; p99Ms?: number }>;
  jvm: { threadsLive: number | null; threadsDaemon: number | null; threadsPeak: number | null };
  hikari: { active: number | null; idle: number | null; pending: number | null; total: number | null };
  outbox: { pending: number };
  readModel: { writeSide: number; readSide: number; lag: number };
  dashboards: {
    settlementOutcomes: { values: Record<string, number>; query: string; source: string };
    backendP95: { query: string; source: string; note: string };
    fraudScores: { scores: number[]; query: string; source: string };
    fraudDecisions: { values: Record<string, number>; query: string; source: string };
  };
}

const KEEP = 120;

export default function MetricsPage() {
  const [history, setHistory] = useState<Snapshot[]>([]);
  const [unreachable, setUnreachable] = useState(false);

  const poll = useCallback(async () => {
    const r = await lab.get<Snapshot>("metrics");
    if (r.ok && r.data) {
      setUnreachable(false);
      setHistory((h) => [...h, r.data!].slice(-KEEP));
    } else setUnreachable(true);
  }, []);
  const mode = useLive({ enabled: true, poll });

  const cur = history[history.length - 1];
  const p95Series = history.map((s) => {
    const t = Object.entries(s.http).find(([k]) => k.endsWith("status 201"));
    return t ? (t[1].p95Ms ?? null) : null;
  });
  const p50Series = history.map((s) => {
    const t = Object.entries(s.http).find(([k]) => k.endsWith("status 201"));
    return t ? (t[1].p50Ms ?? null) : null;
  });

  return (
    <div className="space-y-5">
      <PageHeader title="Metrics" subtitle="A curated snapshot read straight from the backend's meter registry, polled every second while this page is open. Grafana and /actuator/prometheus are not exposed; the four Grafana dashboards are reproduced here.">
        <LiveBadge mode={mode} />
      </PageHeader>
      {unreachable && <p className="font-semibold">The metrics endpoint did not answer.</p>}
      {cur && (
        <>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <Stat label="Outbox pending" value={cur.outbox.pending} />
            <Stat label="Read-model lag" value={cur.readModel.lag} hint={`${cur.readModel.writeSide} written, ${cur.readModel.readSide} projected`} />
            <Stat label="Hikari active / pending" value={`${cur.hikari.active ?? "—"} / ${cur.hikari.pending ?? "—"}`} hint={`idle ${cur.hikari.idle ?? "—"}, total ${cur.hikari.total ?? "—"}`} />
            <Stat label="JVM threads" value={fmtNum(cur.jvm.threadsLive)} hint={`peak ${fmtNum(cur.jvm.threadsPeak)}`} />
            <Stat label="Finalize retries" value={fmtNum(cur.counters.finalizeRetries)} hint="settlement.finalize.retries" />
            <Stat label="UNKNOWN fallbacks" value={fmtNum(cur.counters.unknownFallbacks)} hint="settlement.unknown.fallback" />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card title="Settlement outcome breakdown">
              <BarList title="settlement.outcome by outcome" items={Object.entries(cur.dashboards.settlementOutcomes.values).map(([label, value]) => ({ label, value }))} />
              <div className="mt-3"><Disclosure summary="Query"><p className="mb-1 text-xs font-semibold">{cur.dashboards.settlementOutcomes.source}, the Grafana panel's expression:</p><CodeBox maxHeight="6rem">{cur.dashboards.settlementOutcomes.query}</CodeBox></Disclosure></div>
            </Card>
            <Card title="Backend request latency (POST /settlements, 201)">
              <LineChart title="p50 and p95 of the last 120 samples" unit="ms" series={[{ name: "p50", values: p50Series }, { name: "p95", values: p95Series }]} xLabel="sample (1 s)" />
              <div className="mt-3"><Disclosure summary="Query">
                <p className="mb-1 text-xs font-semibold">{cur.dashboards.backendP95.source}, the Grafana panel's expression:</p><CodeBox maxHeight="6rem">{cur.dashboards.backendP95.query}</CodeBox>
                <p className="mt-2 text-xs font-medium">{cur.dashboards.backendP95.note} Here the percentiles come from the registry's client-side histogram, read directly.</p>
              </Disclosure></div>
            </Card>
            <Card title="Fraud score distribution">
              <Histogram title={`${cur.dashboards.fraudScores.scores.length} assessments`} values={cur.dashboards.fraudScores.scores} />
              <div className="mt-3"><Disclosure summary="Query"><p className="mb-1 text-xs font-semibold">{cur.dashboards.fraudScores.source}, read directly as Grafana does:</p><CodeBox maxHeight="6rem">{cur.dashboards.fraudScores.query}</CodeBox></Disclosure></div>
            </Card>
            <Card title="Fraud decisions">
              <BarList title="decision" items={Object.entries(cur.dashboards.fraudDecisions.values).map(([label, value]) => ({ label, value }))} />
              <div className="mt-3"><Disclosure summary="Query"><p className="mb-1 text-xs font-semibold">{cur.dashboards.fraudDecisions.source}:</p><CodeBox maxHeight="6rem">{cur.dashboards.fraudDecisions.query}</CodeBox></Disclosure></div>
            </Card>
            <Card title="Connection pool and outbox over time">
              <LineChart title="Hikari and outbox" series={[
                { name: "active", values: history.map((s) => s.hikari.active) }, { name: "pending", values: history.map((s) => s.hikari.pending) },
                { name: "outbox pending", values: history.map((s) => s.outbox.pending) }]} xLabel="sample (1 s)" />
            </Card>
            <Card title="Retries and fallbacks (cumulative)">
              <LineChart title="contention counters" series={[
                { name: "finalize retries", values: history.map((s) => s.counters.finalizeRetries) },
                { name: "UNKNOWN fallbacks", values: history.map((s) => s.counters.unknownFallbacks) }]} xLabel="sample (1 s)" />
            </Card>
          </div>

          <Card title="http_server_requests: POST /settlements">
            <Table head={["Timer", "Count", "Mean", "Max", "p50", "p95", "p99"]} empty={Object.keys(cur.http).length === 0 ? "No settlement requests yet." : undefined}>
              {Object.entries(cur.http).map(([name, t]) => (
                <tr key={name}><td className="px-3 py-1.5 font-mono text-xs">{name}</td><td className="px-3 py-1.5 font-mono">{t.count}</td><td className="px-3 py-1.5 font-mono">{fmtMs(t.meanMs)}</td><td className="px-3 py-1.5 font-mono">{fmtMs(t.maxMs)}</td><td className="px-3 py-1.5 font-mono">{fmtMs(t.p50Ms)}</td><td className="px-3 py-1.5 font-mono">{fmtMs(t.p95Ms)}</td><td className="px-3 py-1.5 font-mono">{fmtMs(t.p99Ms)}</td></tr>
              ))}
            </Table>
          </Card>
        </>
      )}
      {!cur && !unreachable && <Card title="Metrics">Loading…</Card>}
    </div>
  );
}
