"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Btn, Card, Field, LiveBadge, PageHeader } from "@/components/ui";
import { lab, streamUrl } from "@/lib/lab/client";
import { fmtTime } from "@/lib/lab/format";
import type { LogEvent } from "@/lib/lab/types";
import { useLive } from "@/lib/lab/useLive";

const MAX_ROWS = 500;
const LEVEL_GLYPH: Record<string, string> = { ERROR: "✗", WARN: "!", WARNING: "!", INFO: "·", DEBUG: "·", TRACE: "·" };

interface Filters {
  service: string;
  level: string;
  loggerPrefix: string;
  requestId: string;
  settlementId: string;
  text: string;
}

function merge(prev: LogEvent[], incoming: LogEvent[]): LogEvent[] {
  if (incoming.length === 0) return prev;
  const seen = new Set(prev.map((e) => `${e.service}:${e.seq}`));
  const fresh = incoming.filter((e) => !seen.has(`${e.service}:${e.seq}`));
  return fresh.length === 0 ? prev : [...prev, ...fresh].sort((a, b) => a.timestamp.localeCompare(b.timestamp)).slice(-MAX_ROWS);
}

export default function LogsPage() {
  const params = useSearchParams();
  const [draft, setDraft] = useState<Filters>({
    service: "", level: "", loggerPrefix: "", requestId: params.get("requestId") ?? "", settlementId: params.get("settlementId") ?? "", text: "",
  });
  const [filters, setFilters] = useState<Filters>(draft);
  const [events, setEvents] = useState<LogEvent[]>([]);
  const [paused, setPaused] = useState(false);
  const lastSeq = useRef<Record<string, number>>({});

  const query = useMemo(() => ({
    service: filters.service, level: filters.level, loggerPrefix: filters.loggerPrefix,
    requestId: filters.requestId, settlementId: filters.settlementId, text: filters.text,
  }), [filters]);

  // Seed with what the ring buffer already holds, matching the filters.
  useEffect(() => {
    let live = true;
    lab.get<{ events: LogEvent[] }>("logs", { ...query, limit: 200 }).then((r) => {
      if (!live || !r.ok || !r.data) return;
      lastSeq.current = {};
      r.data.events.forEach((e) => (lastSeq.current[e.service] = Math.max(lastSeq.current[e.service] ?? 0, e.seq)));
      setEvents(r.data.events.slice(-MAX_ROWS));
    });
    return () => {
      live = false;
    };
  }, [query]);

  const poll = useCallback(async () => {
    // Per-service sequence numbers are independent, so ask for everything newer than the lowest and de-duplicate.
    const lows = Object.values(lastSeq.current);
    const r = await lab.get<{ events: LogEvent[] }>("logs", { ...query, limit: 200, after: lows.length ? Math.min(...lows) : 0 });
    if (r.ok && r.data) {
      r.data.events.forEach((e) => (lastSeq.current[e.service] = Math.max(lastSeq.current[e.service] ?? 0, e.seq)));
      setEvents((prev) => merge(prev, r.data!.events));
    }
  }, [query]);

  const stream = useMemo(() => ({
    url: streamUrl("logs/stream", query),
    events: ["log"],
    onEvent: (_name: string, data: string) => {
      try {
        const e = JSON.parse(data) as LogEvent;
        lastSeq.current[e.service] = Math.max(lastSeq.current[e.service] ?? 0, e.seq);
        setEvents((prev) => merge(prev, [e]));
      } catch {
        /* ignore a malformed line */
      }
    },
  }), [query]);

  const mode = useLive({ enabled: !paused, poll, stream });

  return (
    <div className="space-y-5">
      <PageHeader title="Logs" subtitle="A live tail of both services: the backend's structured events and the fraud service's own, merged into one stream and correlated by X-Request-Id.">
        <div className="flex items-center gap-2"><LiveBadge mode={mode} /><Btn onClick={() => setPaused((p) => !p)}>{paused ? "Resume" : "Pause"}</Btn></div>
      </PageHeader>
      <Card title="Filters">
        <form className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3" onSubmit={(e) => { e.preventDefault(); setFilters(draft); }}>
          <Field label="Service">{(id) => <select id={id} className="neo-input w-full" value={draft.service} onChange={(e) => setDraft({ ...draft, service: e.target.value })}><option value="">both</option><option>backend</option><option>ml-service</option></select>}</Field>
          <Field label="Minimum level">{(id) => <select id={id} className="neo-input w-full" value={draft.level} onChange={(e) => setDraft({ ...draft, level: e.target.value })}><option value="">all</option><option>INFO</option><option>WARN</option><option>ERROR</option></select>}</Field>
          <Field label="Logger prefix">{(id) => <input id={id} className="neo-input w-full" value={draft.loggerPrefix} onChange={(e) => setDraft({ ...draft, loggerPrefix: e.target.value })} placeholder="com.settlementengine" />}</Field>
          <Field label="Request id">{(id) => <input id={id} className="neo-input w-full font-mono text-xs" value={draft.requestId} onChange={(e) => setDraft({ ...draft, requestId: e.target.value })} />}</Field>
          <Field label="Settlement id">{(id) => <input id={id} className="neo-input w-full font-mono text-xs" value={draft.settlementId} onChange={(e) => setDraft({ ...draft, settlementId: e.target.value })} />}</Field>
          <Field label="Text contains">{(id) => <input id={id} className="neo-input w-full" value={draft.text} onChange={(e) => setDraft({ ...draft, text: e.target.value })} />}</Field>
          <div className="flex gap-2 sm:col-span-2 lg:col-span-3">
            <Btn type="submit" tone="yellow">Apply</Btn>
            <Btn type="button" onClick={() => { const empty = { service: "", level: "", loggerPrefix: "", requestId: "", settlementId: "", text: "" }; setDraft(empty); setFilters(empty); }}>Clear</Btn>
          </div>
        </form>
      </Card>
      <Card title={`Tail (${events.length}, newest at the bottom, last ${MAX_ROWS} kept)`}>
        <div className="max-h-[32rem] max-w-full overflow-auto border-2 border-black bg-black p-2 font-mono text-[11px] leading-snug text-[var(--color-neo-cream)]" data-testid="log-tail">
          {events.length === 0 && <p>No matching log lines yet.</p>}
          {events.map((e) => (
            <div key={`${e.service}:${e.seq}`} className="whitespace-pre-wrap break-words border-b border-white/10 py-0.5" data-level={e.level}>
              <span className="opacity-60">{fmtTime(e.timestamp)}</span>{" "}
              <span className="font-bold">{e.service === "ml-service" ? "[ml]" : "[be]"}</span>{" "}
              <span className={e.level === "ERROR" ? "font-black text-[var(--color-neo-red)]" : e.level.startsWith("WARN") ? "font-bold text-[var(--color-neo-orange)]" : ""}>{LEVEL_GLYPH[e.level] ?? "·"} {e.level}</span>{" "}
              <span className="opacity-70">{e.logger.split(".").pop()}</span> {e.message}
              {e.settlementId && <> <Link className="text-[var(--color-neo-yellow)] underline" href={`/lab/playground?inspect=${e.settlementId}`}>settlement {e.settlementId.slice(0, 8)}</Link></>}
              {e.requestId && <> <Link className="text-[var(--color-neo-pink)] underline" href={`/lab/correlate?requestId=${e.requestId}`}>req {e.requestId.slice(0, 10)}</Link></>}
              {e.traceId && <> <Link className="text-[var(--color-neo-green)] underline" href={`/lab/traces?traceId=${e.traceId}`}>trace {e.traceId.slice(0, 8)}</Link></>}
            </div>
          ))}
        </div>
        <p className="mt-2 text-xs font-medium">Click a settlement to open the inspector, a request id to open the correlation view, a trace id to open its waterfall.</p>
      </Card>
    </div>
  );
}
