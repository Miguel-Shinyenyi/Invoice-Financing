"use client";

import { useCallback, useState } from "react";
import { lab } from "@/lib/lab/client";
import { useLive } from "@/lib/lab/useLive";
import type { LabHealth } from "@/lib/lab/types";
import { fmtTime } from "@/lib/lab/format";

/** A status strip on every Lab page: each dependency with status, latency and the time of its last check. */
export function StatusStrip() {
  const [health, setHealth] = useState<LabHealth | null>(null);
  const [unreachable, setUnreachable] = useState(false);

  const poll = useCallback(async () => {
    const res = await lab.get<LabHealth>("health");
    if (res.ok && res.data) {
      setHealth(res.data);
      setUnreachable(false);
    } else {
      setUnreachable(true);
    }
  }, []);
  useLive({ enabled: true, poll, intervalMs: 5000 });

  return (
    <div role="status" aria-label="Sandbox health" className="neo-card flex min-h-11 max-w-full flex-wrap items-center gap-x-4 gap-y-1 rounded-xl px-3.5 py-2 text-[13px]">
      <span className="font-semibold">Sandbox</span>
      {unreachable && <span className="neo-badge tone-red"><span aria-hidden className="mr-1">✗</span>not reachable</span>}
      {!health && !unreachable && <span className="text-muted">checking…</span>}
      {health?.checks.map((c) => (
        <span key={c.name} className="flex items-center gap-1" title={`${c.detail ?? "ok"} · checked ${fmtTime(c.checkedAt)}`}>
          <span aria-hidden className={c.status === "UP" ? "text-[var(--green)]" : "text-[var(--red)]"}>
            {c.status === "UP" ? "●" : "✗"}
          </span>
          <span>{c.name}</span>
          <span className="font-mono text-muted">{c.status === "UP" ? `${c.latencyMs}ms` : "DOWN"}</span>
          <span className="sr-only">{c.status}</span>
        </span>
      ))}
      {health && <span className="ml-auto text-muted">checked {fmtTime(health.checks[0]?.checkedAt)}</span>}
    </div>
  );
}
