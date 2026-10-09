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
    <div role="status" aria-label="Sandbox health" className="neo-card flex max-w-full flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2 text-xs font-bold">
      <span className="font-black uppercase">Sandbox</span>
      {unreachable && <span className="neo-badge bg-[var(--color-neo-red)] text-white"><span aria-hidden className="mr-1">✗</span>not reachable</span>}
      {!health && !unreachable && <span>checking…</span>}
      {health?.checks.map((c) => (
        <span key={c.name} className="flex items-center gap-1" title={`${c.detail ?? "ok"} · checked ${fmtTime(c.checkedAt)}`}>
          <span aria-hidden className={c.status === "UP" ? "text-[var(--color-neo-green)]" : "text-[var(--color-neo-red)]"}>
            {c.status === "UP" ? "●" : "✗"}
          </span>
          <span>{c.name}</span>
          <span className="font-mono font-medium">{c.status === "UP" ? `${c.latencyMs}ms` : "DOWN"}</span>
          <span className="sr-only">{c.status}</span>
        </span>
      ))}
      {health && <span className="ml-auto font-medium opacity-70">checked {fmtTime(health.checks[0]?.checkedAt)}</span>}
    </div>
  );
}
