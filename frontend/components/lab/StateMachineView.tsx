"use client";

import { Fragment } from "react";
import { Card, LiveBadge, Table } from "@/components/ui";
import { useLabJson } from "@/lib/lab/useLabJson";
import type { StateMachine, StateMachines } from "@/lib/lab/types";
import { KnownGap } from "@/components/ui";

interface Placed {
  name: string;
  x: number;
  y: number;
  count: number;
  noCodePath: boolean;
}

/** Layered layout by longest path from the initial state. The edges come from the enum's own canTransitionTo. */
function layout(machine: StateMachine, colW: number, rowH: number): { placed: Placed[]; width: number; height: number } {
  const incoming = new Map<string, string[]>();
  machine.states.forEach((s) => incoming.set(s.name, []));
  machine.transitions.forEach((t) => incoming.get(t.to)?.push(t.from));
  const depth = new Map<string, number>();
  const visit = (name: string, seen: Set<string>): number => {
    if (depth.has(name)) return depth.get(name)!;
    if (seen.has(name)) return 0;
    seen.add(name);
    const parents = incoming.get(name) ?? [];
    const d = parents.length === 0 ? 0 : 1 + Math.max(...parents.map((p) => visit(p, seen)));
    depth.set(name, d);
    return d;
  };
  machine.states.forEach((s) => visit(s.name, new Set()));
  const layers = new Map<number, string[]>();
  machine.states.forEach((s) => {
    const d = depth.get(s.name) ?? 0;
    layers.set(d, [...(layers.get(d) ?? []), s.name]);
  });
  const maxRows = Math.max(...[...layers.values()].map((l) => l.length));
  const placed: Placed[] = machine.states.map((s) => {
    const d = depth.get(s.name) ?? 0;
    const row = (layers.get(d) ?? []).indexOf(s.name);
    const rowsInLayer = (layers.get(d) ?? []).length;
    const yOffset = ((maxRows - rowsInLayer) * rowH) / 2;
    return { name: s.name, x: 10 + d * colW, y: 10 + yOffset + row * rowH, count: s.count, noCodePath: s.noCodePath };
  });
  return { placed, width: 20 + layers.size * colW, height: 20 + maxRows * rowH };
}

function Machine({ title, machine }: { title: string; machine: StateMachine }) {
  const colW = 150;
  const rowH = 62;
  const boxW = 112;
  const boxH = 44;
  const { placed, width, height } = layout(machine, colW, rowH);
  const byName = new Map(placed.map((p) => [p.name, p]));
  return (
    <div className="min-w-0">
      <h3 className="mb-1 text-xs font-semibold">{title}</h3>
      <div className="max-w-full overflow-x-auto rounded-xl bg-surface-2">
        <svg viewBox={`0 0 ${width} ${height}`} width={width} height={height} role="img" aria-label={`${title} state machine`} className="block">
          <defs>
            <marker id={`arrow-${title}`} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" fill="var(--muted)" />
            </marker>
          </defs>
          {machine.transitions.map((t) => {
            const a = byName.get(t.from)!;
            const b = byName.get(t.to)!;
            const x1 = a.x + boxW;
            const y1 = a.y + boxH / 2;
            const x2 = b.x;
            const y2 = b.y + boxH / 2;
            return <line key={`${t.from}-${t.to}`} x1={x1} y1={y1} x2={x2 - 2} y2={y2} stroke="var(--muted)" strokeWidth="1.2" markerEnd={`url(#arrow-${title})`} />;
          })}
          {placed.map((p) => (
            <Fragment key={p.name}>
              <rect x={p.x} y={p.y} width={boxW} height={boxH} rx="10" fill={p.noCodePath ? "var(--surface)" : "var(--blue-fill)"} stroke={p.noCodePath ? "var(--field-edge)" : "var(--tint)"} strokeWidth="1.2" strokeDasharray={p.noCodePath ? "5 3" : ""} />
              <text x={p.x + boxW / 2} y={p.y + 17} textAnchor="middle" fontSize="11" fontWeight="600">{p.name}</text>
              <text x={p.x + boxW / 2} y={p.y + 35} textAnchor="middle" fontSize="13" fontFamily="ui-monospace, SF Mono, Menlo, monospace" fontWeight="500" data-state={p.name} data-count={p.count}>{p.count}</text>
            </Fragment>
          ))}
        </svg>
      </div>
      {machine.states.filter((s) => s.noCodePath).map((s) => (
        <p key={s.name} className="mt-1 text-xs font-medium">
          <strong>{s.name}</strong> (dashed): {s.note}{" "}
          {title === "Settlement" && s.name === "REVERSED" && <KnownGap n={3} anchor="settlement-state-machine" />}
        </p>
      ))}
    </div>
  );
}

export function StateMachineView() {
  const { data, mode } = useLabJson<StateMachines>("state-machines", undefined, 2000);
  if (!data) return <Card title="State machines">Loading…</Card>;
  const statuses = Object.keys(data.readModel.writeSide);
  return (
    <Card title="State machines, drawn from the enums" right={<LiveBadge mode={mode} />}>
      <p className="mb-3 text-xs font-medium">
        Edges come from each enum's own <code className="font-mono">canTransitionTo</code>, never hand-copied. The number in a box is how many rows are in that state right now.
      </p>
      <div className="grid gap-4 lg:grid-cols-2">
        <Machine title="Settlement" machine={data.settlement} />
        <Machine title="Invoice" machine={data.invoice} />
        <Machine title="Advance" machine={data.advance} />
        <div className="min-w-0">
          <h3 className="mb-1 text-xs font-semibold">Write side vs read model</h3>
          <Table head={["Status", "Write side", "Read model", "Lag"]}>
            {statuses.map((s) => {
              const w = data.readModel.writeSide[s] ?? 0;
              const r = data.readModel.readSide[s] ?? 0;
              return (
                <tr key={s} data-status={s}>
                  <td className="px-3 py-1.5 font-semibold">{s}</td>
                  <td className="px-3 py-1.5 font-mono">{w}</td>
                  <td className="px-3 py-1.5 font-mono">{r}</td>
                  <td className="px-3 py-1.5 font-mono">{w - r === 0 ? "in sync" : `${w - r > 0 ? "+" : ""}${w - r}`}</td>
                </tr>
              );
            })}
          </Table>
        </div>
      </div>
    </Card>
  );
}
