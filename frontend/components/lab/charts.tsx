"use client";

// Hand-written SVG charts: no chart library. Each chart is responsive (viewBox, width 100%), carries a text
// legend (colour is never the only signal), and renders nothing but real, measured values.

import { Fragment } from "react";

const PALETTE = ["#2f6fed", "#ff3ea5", "#00c853", "#ff8a00", "#000000", "#ffd100"];
const DASHES = ["", "6 3", "2 3", "8 3 2 3", "", "4 2"];

export interface Series {
  name: string;
  values: Array<number | null>;
}

function niceMax(v: number): number {
  if (v <= 0) return 1;
  const pow = Math.pow(10, Math.floor(Math.log10(v)));
  const n = v / pow;
  const step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
  return step * pow;
}

function fmt(v: number): string {
  if (v >= 1000) return `${(v / 1000).toFixed(v >= 10000 ? 0 : 1)}k`;
  return v % 1 === 0 ? String(v) : v.toFixed(1);
}

function Legend({ names }: { names: string[] }) {
  return (
    <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs font-bold">
      {names.map((n, i) => (
        <li key={n} className="flex items-center gap-1">
          <svg width="22" height="8" aria-hidden>
            <line x1="0" y1="4" x2="22" y2="4" stroke={PALETTE[i % PALETTE.length]} strokeWidth="3" strokeDasharray={DASHES[i % DASHES.length]} />
          </svg>
          {n}
        </li>
      ))}
    </ul>
  );
}

export function LineChart({ title, unit = "", series, xLabel = "second" }: { title: string; unit?: string; series: Series[]; xLabel?: string }) {
  const W = 480;
  const H = 180;
  const L = 38;
  const B = 22;
  const n = Math.max(1, ...series.map((s) => s.values.length));
  const max = niceMax(Math.max(0, ...series.flatMap((s) => s.values.filter((v): v is number => v !== null))));
  const x = (i: number) => L + (n <= 1 ? 0 : (i / (n - 1)) * (W - L - 6));
  const y = (v: number) => 6 + (1 - v / max) * (H - B - 6);
  const empty = series.every((s) => s.values.every((v) => v === null));
  return (
    <figure className="min-w-0">
      <figcaption className="mb-1 text-xs font-black uppercase tracking-wide">
        {title} {unit && <span className="font-medium normal-case">({unit})</span>}
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title} className="h-auto w-full border-2 border-black bg-white">
        {[0, 0.5, 1].map((f) => (
          <Fragment key={f}>
            <line x1={L} x2={W - 6} y1={y(max * f)} y2={y(max * f)} stroke="#000" strokeOpacity="0.15" />
            <text x={L - 4} y={y(max * f) + 3} textAnchor="end" fontSize="9" fontFamily="monospace">{fmt(max * f)}</text>
          </Fragment>
        ))}
        {series.map((s, si) => {
          const pts = s.values.map((v, i) => (v === null ? null : [x(i), y(v)] as const));
          const segments: string[] = [];
          let current = "";
          pts.forEach((p) => {
            if (p === null) {
              if (current) segments.push(current);
              current = "";
            } else current += `${current ? "L" : "M"}${p[0].toFixed(1)},${p[1].toFixed(1)} `;
          });
          if (current) segments.push(current);
          const last = [...pts].reverse().find((p) => p !== null);
          return (
            <Fragment key={s.name}>
              {segments.map((d, i) => (
                <path key={i} d={d} fill="none" stroke={PALETTE[si % PALETTE.length]} strokeWidth="2.5" strokeDasharray={DASHES[si % DASHES.length]} />
              ))}
              {last && <circle cx={last[0]} cy={last[1]} r="3" fill={PALETTE[si % PALETTE.length]} stroke="#000" />}
            </Fragment>
          );
        })}
        <text x={W / 2} y={H - 5} textAnchor="middle" fontSize="9" fontFamily="monospace">{xLabel} →</text>
        {empty && <text x={W / 2} y={H / 2} textAnchor="middle" fontSize="11" fontWeight="bold">no data yet</text>}
      </svg>
      <Legend names={series.map((s) => s.name)} />
    </figure>
  );
}

export interface StackSeries {
  name: string;
  values: number[];
}

/** One stacked bar per sample; each segment is a value measured in that sample. */
export function StackedBars({ title, unit = "", series, xLabel = "second" }: { title: string; unit?: string; series: StackSeries[]; xLabel?: string }) {
  const W = 480;
  const H = 180;
  const L = 38;
  const B = 22;
  const n = Math.max(1, ...series.map((s) => s.values.length));
  const totals = Array.from({ length: n }, (_, i) => series.reduce((a, s) => a + (s.values[i] ?? 0), 0));
  const max = niceMax(Math.max(0, ...totals));
  const bw = Math.max(2, (W - L - 6) / n - 2);
  return (
    <figure className="min-w-0">
      <figcaption className="mb-1 text-xs font-black uppercase tracking-wide">
        {title} {unit && <span className="font-medium normal-case">({unit})</span>}
      </figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title} className="h-auto w-full border-2 border-black bg-white">
        {[0, 0.5, 1].map((f) => (
          <Fragment key={f}>
            <line x1={L} x2={W - 6} y1={6 + (1 - f) * (H - B - 6)} y2={6 + (1 - f) * (H - B - 6)} stroke="#000" strokeOpacity="0.15" />
            <text x={L - 4} y={6 + (1 - f) * (H - B - 6) + 3} textAnchor="end" fontSize="9" fontFamily="monospace">{fmt(max * f)}</text>
          </Fragment>
        ))}
        {Array.from({ length: n }, (_, i) => {
          let acc = 0;
          return (
            <Fragment key={i}>
              {series.map((s, si) => {
                const v = s.values[i] ?? 0;
                const h = (v / max) * (H - B - 6);
                const yTop = 6 + (H - B - 6) - ((acc + v) / max) * (H - B - 6);
                acc += v;
                return h > 0 ? (
                  <rect key={s.name} x={L + i * (bw + 2)} y={yTop} width={bw} height={h} fill={PALETTE[si % PALETTE.length]} stroke="#000" strokeWidth="0.6">
                    <title>{`${s.name}: ${v} (${xLabel} ${i + 1})`}</title>
                  </rect>
                ) : null;
              })}
            </Fragment>
          );
        })}
        <text x={W / 2} y={H - 5} textAnchor="middle" fontSize="9" fontFamily="monospace">{xLabel} →</text>
        {totals.every((t) => t === 0) && <text x={W / 2} y={H / 2} textAnchor="middle" fontSize="11" fontWeight="bold">no data yet</text>}
      </svg>
      <Legend names={series.map((s) => s.name)} />
    </figure>
  );
}

/** Horizontal bars with the value written next to each bar. */
export function BarList({ title, items, unit = "" }: { title: string; items: Array<{ label: string; value: number }>; unit?: string }) {
  const max = Math.max(1, ...items.map((i) => i.value));
  return (
    <figure className="min-w-0">
      <figcaption className="mb-2 text-xs font-black uppercase tracking-wide">{title}</figcaption>
      <ul className="space-y-1.5">
        {items.map((it, i) => (
          <li key={it.label} className="grid grid-cols-[6.5rem_1fr_auto] items-center gap-2 text-xs font-bold">
            <span className="truncate">{it.label}</span>
            <span className="block h-4 border-2 border-black bg-white">
              <span className="block h-full" style={{ width: `${(it.value / max) * 100}%`, background: PALETTE[i % PALETTE.length] }} />
            </span>
            <span className="font-mono">{fmt(it.value)}{unit}</span>
          </li>
        ))}
        {items.length === 0 && <li className="text-xs font-medium">no data yet</li>}
      </ul>
    </figure>
  );
}

/** Counts of values falling in equal-width buckets between lo and hi. */
export function Histogram({ title, values, lo = 0, hi = 1, buckets = 10 }: { title: string; values: number[]; lo?: number; hi?: number; buckets?: number }) {
  const counts = new Array(buckets).fill(0) as number[];
  for (const v of values) {
    const idx = Math.min(buckets - 1, Math.max(0, Math.floor(((v - lo) / (hi - lo)) * buckets)));
    counts[idx]++;
  }
  const max = Math.max(1, ...counts);
  const W = 480;
  const H = 150;
  const bw = (W - 30) / buckets;
  return (
    <figure className="min-w-0">
      <figcaption className="mb-1 text-xs font-black uppercase tracking-wide">{title}</figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title} className="h-auto w-full border-2 border-black bg-white">
        {counts.map((c, i) => {
          const h = (c / max) * (H - 36);
          return (
            <Fragment key={i}>
              <rect x={24 + i * bw} y={H - 22 - h} width={bw - 3} height={h} fill={PALETTE[0]} stroke="#000" strokeWidth="0.8" />
              {c > 0 && <text x={24 + i * bw + (bw - 3) / 2} y={H - 25 - h} textAnchor="middle" fontSize="9" fontFamily="monospace">{c}</text>}
              <text x={24 + i * bw + (bw - 3) / 2} y={H - 8} textAnchor="middle" fontSize="8" fontFamily="monospace">
                {(lo + (i * (hi - lo)) / buckets).toFixed(1)}
              </text>
            </Fragment>
          );
        })}
        {values.length === 0 && <text x={W / 2} y={H / 2} textAnchor="middle" fontSize="11" fontWeight="bold">no data yet</text>}
      </svg>
    </figure>
  );
}
