"use client";

import Link from "next/link";
import { useId, type ButtonHTMLAttributes, type ReactNode } from "react";
import { DOCS_BASE } from "@/lib/config";

/** Shared neo-brutalist primitives. Colour is never the only signal: statuses carry a glyph and text. */

export function PageHeader({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children?: ReactNode }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-2xl font-black uppercase tracking-tight text-black">{title}</h1>
        {subtitle && <p className="mt-1 max-w-3xl text-sm font-medium text-black/80">{subtitle}</p>}
      </div>
      {children}
    </div>
  );
}

export function Card({
  title,
  right,
  children,
  className = "",
  id,
}: {
  title?: ReactNode;
  right?: ReactNode;
  children: ReactNode;
  className?: string;
  id?: string;
}) {
  return (
    <section id={id} className={`neo-card min-w-0 p-4 ${className}`}>
      {(title || right) && (
        <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
          {title && <h2 className="text-sm font-black uppercase tracking-wide text-black">{title}</h2>}
          {right}
        </header>
      )}
      {children}
    </section>
  );
}

const TONES: Record<string, string> = {
  default: "bg-white",
  yellow: "bg-[var(--color-neo-yellow)]",
  pink: "bg-[var(--color-neo-pink)] text-black",
  blue: "bg-[var(--color-neo-blue)] text-white",
  green: "bg-[var(--color-neo-green)] text-black",
  red: "bg-[var(--color-neo-red)] text-white",
  orange: "bg-[var(--color-neo-orange)] text-black",
};

export function Btn({
  tone = "default",
  busy = false,
  children,
  className = "",
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: keyof typeof TONES; busy?: boolean }) {
  return (
    <button {...rest} disabled={rest.disabled || busy} className={`neo-btn ${TONES[tone]} ${className}`}>
      {busy ? "Working…" : children}
    </button>
  );
}

export function Stat({ label, value, hint, tone = "default" }: { label: string; value: ReactNode; hint?: ReactNode; tone?: keyof typeof TONES }) {
  return (
    <div className={`border-2 border-black p-3 ${TONES[tone]} min-w-0`}>
      <div className="text-[11px] font-black uppercase tracking-wide">{label}</div>
      <div className="mt-1 break-words font-mono text-xl font-black">{value}</div>
      {hint && <div className="mt-1 text-xs font-medium opacity-80">{hint}</div>}
    </div>
  );
}

/** "Known gap n", linked to the exact section of docs/reconciliation.md (or another doc) it is recorded in. */
export function KnownGap({ n, anchor, doc = "docs/reconciliation.md", children }: { n: number; anchor: string; doc?: string; children?: ReactNode }) {
  return (
    <a
      href={`${DOCS_BASE}/${doc}#${anchor}`}
      target="_blank"
      rel="noreferrer"
      className="inline-flex items-center gap-1 border-2 border-black bg-[var(--color-neo-orange)] px-2 py-0.5 text-xs font-black uppercase shadow-brutal-sm"
    >
      <span aria-hidden>⚠</span> Known gap {n}
      {children && <span className="ml-1 normal-case font-bold">{children}</span>}
    </a>
  );
}

export function DocLink({ path, anchor, children }: { path: string; anchor?: string; children: ReactNode }) {
  return (
    <a href={`${DOCS_BASE}/${path}${anchor ? `#${anchor}` : ""}`} target="_blank" rel="noreferrer" className="font-bold underline">
      {children}
    </a>
  );
}

export function Notice({ tone = "yellow", title, children }: { tone?: keyof typeof TONES; title?: string; children: ReactNode }) {
  const icon = tone === "red" ? "✗" : tone === "green" ? "✓" : tone === "blue" ? "ℹ" : "!";
  return (
    <div role="note" className={`border-2 border-black p-3 text-sm ${TONES[tone]}`}>
      <span aria-hidden className="mr-2 font-black">{icon}</span>
      {title && <strong className="mr-1 font-black">{title}</strong>}
      {children}
    </div>
  );
}

export function Disclosure({ summary, children, open = false }: { summary: ReactNode; children: ReactNode; open?: boolean }) {
  return (
    <details open={open} className="border-2 border-black bg-white">
      <summary className="cursor-pointer select-none px-3 py-2 text-xs font-black uppercase tracking-wide">{summary}</summary>
      <div className="border-t-2 border-black p-3">{children}</div>
    </details>
  );
}

/** Code and log lines scroll inside their own box so the page never scrolls sideways. */
export function CodeBox({ children, maxHeight = "20rem" }: { children: ReactNode; maxHeight?: string }) {
  return (
    <pre style={{ maxHeight }} className="max-w-full overflow-auto border-2 border-black bg-black p-3 font-mono text-xs leading-relaxed text-[var(--color-neo-cream)]">
      {children}
    </pre>
  );
}

export function Table({ head, children, empty }: { head: string[]; children: ReactNode; empty?: string }) {
  return (
    <div className="max-w-full overflow-x-auto border-2 border-black">
      <table className="w-full min-w-max text-left text-sm">
        <thead className="border-b-2 border-black bg-[var(--color-neo-yellow)] text-xs font-black uppercase tracking-wide">
          <tr>
            {head.map((h) => (
              <th key={h} className="px-3 py-2">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-black/20 font-medium">{children}</tbody>
      </table>
      {empty && <p className="p-3 text-sm font-medium">{empty}</p>}
    </div>
  );
}

export function Field({ label, children, hint }: { label: string; children: (id: string) => ReactNode; hint?: ReactNode }) {
  const id = useId();
  return (
    <div className="min-w-0">
      <label htmlFor={id} className="mb-1 block text-xs font-black uppercase tracking-wide">{label}</label>
      {children(id)}
      {hint && <p className="mt-1 text-xs font-medium text-black/70">{hint}</p>}
    </div>
  );
}

export function NavChip({ href, active, children }: { href: string; active?: boolean; children: ReactNode }) {
  return (
    <Link href={href} aria-current={active ? "page" : undefined} className={`neo-chip whitespace-nowrap ${active ? "bg-[var(--color-neo-yellow)]" : "bg-white"}`}>
      {children}
    </Link>
  );
}

export function LiveBadge({ mode }: { mode: string }) {
  const text = mode === "sse" ? "live: SSE" : mode === "polling" ? "live: polling 1s" : mode === "paused" ? "paused" : "connecting";
  const icon = mode === "sse" ? "●" : mode === "polling" ? "◐" : mode === "paused" ? "❚❚" : "…";
  return (
    <span className="neo-badge bg-white" title="How this panel receives updates">
      <span aria-hidden className="mr-1">{icon}</span>
      {text}
    </span>
  );
}
