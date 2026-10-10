"use client";

import { useCallback, useRef, useState } from "react";
import { Card, LiveBadge } from "@/components/ui";
import { lab } from "@/lib/lab/client";
import { fmtNum, fmtTime } from "@/lib/lab/format";
import type { SystemNode } from "@/lib/lab/types";
import { useLive } from "@/lib/lab/useLive";

interface NodeDef {
  id: string;
  title: string;
  what: string;
  unit: string;
}

// The request path, in the order the engine does it (SettlementService.createSettlement).
const FLOW: NodeDef[] = [
  { id: "client", title: "Client", what: "POST /settlements with an Idempotency-Key", unit: "keys seen" },
  { id: "idempotency", title: "Idempotency check", what: "claim the key, or return / 409", unit: "keys" },
  { id: "pending", title: "Pending write", what: "settlement PENDING, same transaction", unit: "settlements" },
  { id: "gateway", title: "Gateway", what: "external call; outcome or exception", unit: "answered" },
  { id: "finalize", title: "Finalize", what: "status, ledger entries, outbox event", unit: "ledger entries" },
  { id: "outbox", title: "Outbox publisher", what: "polls and sends to Kafka", unit: "published" },
  { id: "kafka", title: "Kafka", what: "six topics", unit: "messages" },
  { id: "readmodel", title: "Read model", what: "last-write-wins projection", unit: "rows" },
];

// The loops around it.
const LOOPS: NodeDef[] = [
  { id: "reconciliation", title: "Reconciliation loop", what: "compares settlements with external records", unit: "runs" },
  { id: "sweep", title: "Stale-pending sweep", what: "UNKNOWN with no reference (stranded)", unit: "stranded" },
  { id: "ledgercheck", title: "Ledger check", what: "on every GET /accounts/{id}", unit: "checks + mismatches" },
  { id: "fraud", title: "Fraud service", what: "POST /score before financing", unit: "assessments" },
];

/**
 * Each node shows a real counter from the sandbox and is outlined for a moment only when that counter actually
 * moved between two polls, that is, when a real event arrived. Nothing here moves on its own.
 */
export function SystemMap() {
  const [nodes, setNodes] = useState<Record<string, SystemNode>>({});
  const [changed, setChanged] = useState<Record<string, boolean>>({});
  const previous = useRef<Record<string, number>>({});

  const poll = useCallback(async () => {
    const res = await lab.get<{ nodes: SystemNode[] }>("system");
    if (!res.ok || !res.data) return;
    const next: Record<string, SystemNode> = {};
    const flash: Record<string, boolean> = {};
    for (const n of res.data.nodes) {
      next[n.id] = n;
      const before = previous.current[n.id];
      if (before !== undefined && before !== n.count) flash[n.id] = true;
      previous.current[n.id] = n.count;
    }
    setNodes(next);
    setChanged(flash);
  }, []);
  const mode = useLive({ enabled: true, poll });

  const box = (d: NodeDef) => {
    const n = nodes[d.id];
    return (
      <li
        key={`${d.id}-${n?.count ?? "x"}`}
        data-node={d.id}
        data-count={n?.count ?? ""}
        className={`min-w-0 rounded-xl bg-surface-2 p-2 ${changed[d.id] ? "lab-flash" : ""}`}
      >
        <div className="text-xs font-semibold">{d.title}</div>
        <div className="font-mono text-lg font-semibold">{n ? fmtNum(n.count) : "…"} <span className="text-[11px] font-semibold">{d.unit}</span></div>
        <div className="text-[11px] font-medium leading-tight">{d.what}</div>
        {n?.lastEventAt && <div className="mt-1 text-[10px] font-medium text-muted">last {fmtTime(n.lastEventAt)}</div>}
      </li>
    );
  };

  return (
    <Card title="How a settlement flows" right={<LiveBadge mode={mode} />}>
      <p className="mb-3 text-xs font-medium">
        Counters are read from the sandbox's tables and Kafka. A node is outlined only when its counter moved because something really happened there.
      </p>
      <ol className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
        {FLOW.map((d, i) => (
          <li key={d.id} className="relative list-none">
            <ul className="contents">{box(d)}</ul>
            {i < FLOW.length - 1 && <span aria-hidden className="absolute -bottom-2 left-1/2 z-10 text-lg font-semibold leading-none lg:hidden">↓</span>}
            {i < FLOW.length - 1 && i % 4 !== 3 && <span aria-hidden className="absolute -right-2.5 top-1/2 z-10 hidden -translate-y-1/2 text-lg font-semibold lg:block">→</span>}
          </li>
        ))}
      </ol>
      <h3 className="mb-2 mt-5 text-xs font-semibold">Around it</h3>
      <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">{LOOPS.map(box)}</ul>
    </Card>
  );
}
