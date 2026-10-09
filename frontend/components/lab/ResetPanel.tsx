"use client";

import { useState, useSyncExternalStore } from "react";
import { Btn, Card, Notice, Stat } from "@/components/ui";
import { ShowRequest } from "@/components/lab/ShowRequest";
import { lab, type LabResult } from "@/lib/lab/client";
import { countdown, fmtDateTime } from "@/lib/lab/format";
import type { LabStatus } from "@/lib/lab/types";
import { useLabJson } from "@/lib/lab/useLabJson";

// A once-a-second clock for countdowns only. It displays the distance to a time the server announced; it is not an animation.
const subscribeSecond = (cb: () => void) => {
  const t = setInterval(cb, 1000);
  return () => clearInterval(t);
};

export function useNow(): number {
  return useSyncExternalStore(subscribeSecond, () => Math.floor(Date.now() / 1000) * 1000, () => 0);
}

export function ResetPanel() {
  const { data: status, refresh } = useLabJson<LabStatus>("status", undefined, 5000);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<LabResult<unknown> | null>(null);
  const now = useNow();

  async function reset() {
    setBusy(true);
    const r = await lab.post("reset");
    setResult(r);
    setBusy(false);
    await refresh();
  }

  return (
    <Card title="Reset the sandbox">
      <div className="grid gap-2 sm:grid-cols-3">
        <Stat label="Last reset" value={<span className="text-sm">{fmtDateTime(status?.reset.lastResetAt)}</span>} />
        <Stat label="Next automatic reset" value={now ? countdown(status?.reset.nextResetAt, now) : "—"} hint={`every ${status?.reset.autoResetMinutes ?? "—"} minutes`} />
        <div className="flex items-end"><Btn tone="pink" busy={busy} onClick={reset} className="w-full">Reset now</Btn></div>
      </div>
      <p className="mt-2 text-xs font-medium">
        A reset truncates the data tables, reloads the seed, and clears the in-memory external record store and payment source
        (<strong>Known gap 5</strong>: both live in memory). Limited to once per {status?.reset.cooldownSeconds ?? 60} seconds.
      </p>
      {result && !result.ok && <div className="mt-2"><Notice tone="yellow">{result.message ?? "The reset was refused."}</Notice></div>}
      {result?.ok && <div className="mt-2"><Notice tone="green">Reset done.</Notice></div>}
      <div className="mt-2"><ShowRequest sent={result?.sent ?? null} /></div>
    </Card>
  );
}
