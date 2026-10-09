export function fmtMs(v: number | null | undefined): string {
  if (v === null || v === undefined) return "—";
  return v >= 100 ? `${Math.round(v)} ms` : `${v.toFixed(1)} ms`;
}

export function fmtNum(v: number | null | undefined, digits = 0): string {
  if (v === null || v === undefined) return "—";
  return v.toLocaleString("en-US", { maximumFractionDigits: digits, minimumFractionDigits: digits });
}

export function fmtMoney(v: number | string | null | undefined, currency = "USD"): string {
  if (v === null || v === undefined) return "—";
  return `${Number(v).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
}

export function shortId(id: string | null | undefined): string {
  return id ? `${id.slice(0, 8)}…` : "—";
}

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString("en-GB", { hour12: false });
}

export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString("en-GB", { hour12: false });
}

export function fmtMicros(us: number): string {
  if (us >= 1_000_000) return `${(us / 1_000_000).toFixed(2)} s`;
  if (us >= 1000) return `${(us / 1000).toFixed(us >= 100_000 ? 0 : 1)} ms`;
  return `${us} µs`;
}

export function countdown(targetIso: string | null | undefined, nowMs: number): string {
  if (!targetIso) return "—";
  const left = Math.max(0, Math.round((new Date(targetIso).getTime() - nowMs) / 1000));
  const m = Math.floor(left / 60);
  const s = left % 60;
  return m > 0 ? `${m}m ${String(s).padStart(2, "0")}s` : `${s}s`;
}
