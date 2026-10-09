"use client";

import { fmtMicros } from "@/lib/lab/format";

export interface Span {
  spanId: string;
  parentSpanId: string | null;
  service: string;
  operation: string;
  kind: string;
  offsetMicros: number;
  durationMicros: number;
  error: boolean;
  attributes: Record<string, string>;
}

export interface Trace {
  traceId: string;
  durationMicros: number;
  spanCount: number;
  services: string[];
  spans: Span[];
}

const KIND_FILL: Record<string, string> = { sql: "#2f6fed", kafka: "#ff3ea5", "http-client": "#ff8a00", "http-server": "#00c853", internal: "#ffd100", messaging: "#ff3ea5" };
const KIND_LABEL: Record<string, string> = { sql: "SQL", kafka: "Kafka", "http-client": "outbound HTTP", "http-server": "inbound HTTP", internal: "internal", messaging: "messaging" };

/** A hand-written SVG span waterfall: one bar per span, positioned by its real start offset and duration. */
export function Waterfall({ trace }: { trace: Trace }) {
  const depth = new Map<string, number>();
  const byId = new Map(trace.spans.map((s) => [s.spanId, s]));
  const depthOf = (s: Span): number => {
    if (depth.has(s.spanId)) return depth.get(s.spanId)!;
    const parent = s.parentSpanId ? byId.get(s.parentSpanId) : undefined;
    const d = parent ? depthOf(parent) + 1 : 0;
    depth.set(s.spanId, d);
    return d;
  };
  const total = Math.max(1, trace.durationMicros);
  return (
    <div data-testid="waterfall" className="space-y-1.5">
      <p className="text-xs font-medium">
        Trace <code className="break-all font-mono font-bold">{trace.traceId}</code> · {trace.spanCount} spans · {fmtMicros(trace.durationMicros)} · services: {trace.services.join(", ")}
      </p>
      <ul className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] font-bold">
        {Object.entries(KIND_LABEL).filter(([k]) => k !== "messaging").map(([k, label]) => (
          <li key={k} className="flex items-center gap-1"><svg width="12" height="12" aria-hidden><rect width="12" height="12" fill={KIND_FILL[k]} stroke="#000" /></svg>{label}</li>
        ))}
      </ul>
      {trace.spans.map((s) => {
        const left = (s.offsetMicros / total) * 100;
        const width = Math.max(0.6, (s.durationMicros / total) * 100);
        return (
          <details key={s.spanId} className="border-2 border-black bg-white" data-kind={s.kind}>
            <summary className="cursor-pointer select-none px-2 py-1" style={{ paddingLeft: 8 + depthOf(s) * 14 }}>
              <span className="text-xs font-black">{s.error && <span aria-label="error">✗ </span>}{s.service}</span>{" "}
              <span className="break-all font-mono text-xs">{s.operation}</span>{" "}
              <span className="neo-badge bg-white text-[10px]">{KIND_LABEL[s.kind] ?? s.kind}</span>{" "}
              <span className="font-mono text-xs font-bold">{fmtMicros(s.durationMicros)}</span>
              <svg viewBox="0 0 100 8" preserveAspectRatio="none" className="mt-1 block h-3 w-full" role="img" aria-label={`${s.operation} starts at ${left.toFixed(1)}% and lasts ${fmtMicros(s.durationMicros)}`}>
                <rect x="0" y="0" width="100" height="8" fill="#f5f5f5" />
                <rect x={left} y="0" width={width} height="8" fill={s.error ? "#ff3b30" : KIND_FILL[s.kind] ?? "#ffd100"} stroke="#000" strokeWidth="0.4" vectorEffect="non-scaling-stroke" />
              </svg>
            </summary>
            <dl className="grid gap-x-3 gap-y-0.5 border-t-2 border-black p-2 text-[11px] sm:grid-cols-[max-content_1fr]">
              <dt className="font-black">span id</dt><dd className="break-all font-mono">{s.spanId}</dd>
              {Object.entries(s.attributes).map(([k, v]) => (<><dt key={`k${k}`} className="font-black">{k}</dt><dd key={`v${k}`} className="break-all font-mono">{v}</dd></>))}
            </dl>
          </details>
        );
      })}
    </div>
  );
}
