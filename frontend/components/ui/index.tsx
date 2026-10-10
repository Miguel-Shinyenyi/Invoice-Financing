"use client";

import Link from "next/link";
import { useId, type ButtonHTMLAttributes, type ReactNode } from "react";
import { DOCS_BASE } from "@/lib/config";

/** Shared primitives, Apple-paradigm (docs/design-apple.md): grouped white cards on a gray canvas,
 *  sentence-case semibold type, one blue tint, system colours for status only.
 *  Colour is never the only signal: statuses carry a glyph and text. */

export function PageHeader({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-[28px] font-semibold leading-tight tracking-tight text-ink sm:text-[34px]">{title}</h1>
        {subtitle && <p className="mt-2 max-w-[68ch] text-[15px] leading-relaxed text-muted">{subtitle}</p>}
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
    <section id={id} className={`neo-card min-w-0 p-4 sm:p-5 ${className}`}>
      {(title || right) && (
        <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
          {title && <h2 className="text-[17px] font-semibold tracking-tight text-ink">{title}</h2>}
          {right}
        </header>
      )}
      {children}
    </section>
  );
}

const TONES: Record<string, string> = {
  default: "tone-default",
  yellow: "tone-yellow",
  pink: "tone-pink",
  blue: "tone-blue",
  green: "tone-green",
  red: "tone-red",
  orange: "tone-orange",
};

/** `tone="yellow"` is the primary (filled tint) button, kept for compatibility with existing call sites. */
export function Btn({
  tone = "default",
  busy = false,
  children,
  className = "",
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: keyof typeof TONES; busy?: boolean }) {
  return (
    <button {...rest} disabled={rest.disabled || busy} aria-busy={busy || undefined} className={`neo-btn ${tone === "default" ? "" : TONES[tone]} ${className}`}>
      {busy ? "Working…" : children}
    </button>
  );
}

export function Stat({ label, value, hint, tone = "default" }: { label: string; value: ReactNode; hint?: ReactNode; tone?: keyof typeof TONES }) {
  const surface = tone === "default" ? "bg-surface-2 text-ink" : TONES[tone];
  return (
    <div className={`min-w-0 rounded-xl p-3 ${surface}`}>
      <div className={`text-[12px] font-medium ${tone === "default" ? "text-muted" : "opacity-90"}`}>{label}</div>
      <div className="mt-0.5 break-words font-display text-[22px] font-semibold tabular-nums tracking-tight">{value}</div>
      {hint && <div className={`mt-0.5 text-[12px] ${tone === "default" ? "text-muted" : "opacity-90"}`}>{hint}</div>}
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
      className="tone-orange inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[12px] font-semibold hover:underline"
    >
      <span aria-hidden>⚠</span> Known gap {n}
      {children && <span className="ml-1 font-medium">{children}</span>}
    </a>
  );
}

export function DocLink({ path, anchor, children }: { path: string; anchor?: string; children: ReactNode }) {
  return (
    <a href={`${DOCS_BASE}/${path}${anchor ? `#${anchor}` : ""}`} target="_blank" rel="noreferrer" className="font-medium text-link underline underline-offset-2">
      {children}
    </a>
  );
}

export function Notice({ tone = "yellow", title, children }: { tone?: keyof typeof TONES; title?: string; children: ReactNode }) {
  const icon = tone === "red" ? "✗" : tone === "green" ? "✓" : tone === "blue" ? "ℹ" : "!";
  // Body text stays ink-coloured for reading; the tone colours the glyph and title.
  return (
    <div role="note" className={`flex gap-3 rounded-xl p-3.5 text-[14px] leading-relaxed ${TONES[tone]}`}>
      <span aria-hidden className="mt-px flex size-5 shrink-0 items-center justify-center rounded-full bg-current text-[11px] font-semibold">
        <span className="text-[var(--surface)]">{icon}</span>
      </span>
      <div className="min-w-0 text-ink">
        {title && <strong className="mr-1 font-semibold">{title}</strong>}
        {children}
      </div>
    </div>
  );
}

export function Disclosure({ summary, children, open = false }: { summary: ReactNode; children: ReactNode; open?: boolean }) {
  return (
    <details open={open} className="group overflow-hidden rounded-xl bg-surface-2">
      <summary className="flex min-h-11 cursor-pointer select-none list-none items-center justify-between gap-2 px-3.5 py-2 text-[14px] font-medium text-ink [&::-webkit-details-marker]:hidden">
        <span className="min-w-0">{summary}</span>
        <svg aria-hidden width="12" height="12" viewBox="0 0 12 12" className="shrink-0 text-muted transition-transform duration-200 group-open:rotate-90 motion-reduce:transition-none">
          <path d="M4 2l4 4-4 4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </summary>
      <div className="border-t border-hairline p-3.5">{children}</div>
    </details>
  );
}

/** Code and log lines scroll inside their own box so the page never scrolls sideways. */
export function CodeBox({ children, maxHeight = "20rem" }: { children: ReactNode; maxHeight?: string }) {
  return (
    <pre style={{ maxHeight }} className="max-w-full overflow-auto rounded-xl bg-[var(--code-bg)] p-3.5 font-mono text-[12.5px] leading-relaxed text-[var(--code-ink)]">
      {children}
    </pre>
  );
}

export function Table({ head, children, empty }: { head: string[]; children: ReactNode; empty?: string }) {
  return (
    <div className="max-w-full overflow-x-auto rounded-xl bg-surface ring-1 ring-hairline ring-inset">
      <table className="w-full min-w-max text-left text-[14px]">
        <thead className="border-b border-hairline text-[12px] font-medium text-muted">
          <tr>
            {head.map((h) => (
              <th key={h} scope="col" className="px-3.5 py-2.5 font-medium">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-hairline text-ink [&_td]:px-3.5 [&_td]:py-2.5">{children}</tbody>
      </table>
      {empty && <p className="p-3.5 text-[14px] text-muted">{empty}</p>}
    </div>
  );
}

export function Field({ label, children, hint }: { label: string; children: (id: string) => ReactNode; hint?: ReactNode }) {
  const id = useId();
  return (
    <div className="min-w-0">
      <label htmlFor={id} className="mb-1 block text-[13px] font-medium text-ink">{label}</label>
      {children(id)}
      {hint && <p className="mt-1 text-[12px] text-muted">{hint}</p>}
    </div>
  );
}

export function NavChip({ href, active, children }: { href: string; active?: boolean; children: ReactNode }) {
  return (
    <Link href={href} aria-current={active ? "page" : undefined} className="neo-chip whitespace-nowrap">
      {children}
    </Link>
  );
}

export function LiveBadge({ mode }: { mode: string }) {
  // Text is asserted by e2e/load-caps.spec.ts: keep it.
  const text = mode === "sse" ? "live: SSE" : mode === "polling" ? "live: polling 1s" : mode === "paused" ? "paused" : "connecting";
  const live = mode === "sse" || mode === "polling";
  return (
    <span className={`neo-badge ${live ? "tone-green" : ""}`} title="How this panel receives updates">
      <span aria-hidden className={`mr-1 inline-block size-1.5 rounded-full ${live ? "bg-current" : "bg-[var(--muted)]"}`} />
      {text}
    </span>
  );
}
