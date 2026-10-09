"use client";

import { Card, Stat } from "@/components/ui";
import type { LabStatus } from "@/lib/lab/types";
import { useLabJson } from "@/lib/lab/useLabJson";

/** The caps and the compressed timings, read from the server so the UI never hard-codes them. */
export function CapsPanel() {
  const { data } = useLabJson<LabStatus>("status", undefined, 5000);
  if (!data) return <Card title="Limits and timings">Loading…</Card>;
  const c = data.caps;
  const t = data.timeCompression;
  return (
    <Card title="Limits and timings (from the server)">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Stat label="Virtual users" value={`≤ ${c.maxVirtualUsers}`} />
        <Stat label="Run length" value={`≤ ${c.maxDurationSeconds}s`} />
        <Stat label="Requests per run" value={`≤ ${c.maxTotalRequests}`} />
        <Stat label="Between runs" value={`${c.runCooldownSeconds}s`} />
        <Stat label="Amount" value={`${c.minAmount}–${c.maxAmount}`} />
        <Stat label="Actions / min / IP" value={c.requestsPerMinutePerIp} />
        <Stat label="Slow gateway" value={`≤ ${c.maxSlowMs} ms`} />
        <Stat label="Live stream" value={`≤ ${c.sseIdleTimeoutMinutes} min`} hint={`${c.sseMaxPerIp} per IP`} />
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Stat label="Reconciliation + sweep" value={`every ${t.reconciliationIntervalMs / 1000}s`} />
        <Stat label="Reconciliation grace" value={`${t.reconciliationGraceSeconds}s`} />
        <Stat label="Stale-pending grace" value={`${t.stalePendingGraceSeconds}s`} />
        <Stat label="Outbox poll" value={`${t.outboxPollMs} ms`} />
      </div>
      <p className="mt-2 text-xs font-medium">These use the engine's real configuration properties, compressed so you see whole cycles in about a minute. Staging uses 60s, 300s, 300s and 500ms.</p>
    </Card>
  );
}
