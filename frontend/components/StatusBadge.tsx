const COLORS: Record<string, string> = {
  CONFIRMED: "bg-[var(--color-neo-green)] text-black",
  DISBURSED: "bg-[var(--color-neo-green)] text-black",
  REPAID: "bg-[var(--color-neo-green)] text-black",
  RESOLVED: "bg-[var(--color-neo-green)] text-black",
  ALLOW: "bg-[var(--color-neo-green)] text-black",
  PASS: "bg-[var(--color-neo-green)] text-black",
  UP: "bg-[var(--color-neo-green)] text-black",
  PENDING: "bg-[var(--color-neo-yellow)] text-black",
  ISSUED: "bg-[var(--color-neo-yellow)] text-black",
  FINANCED: "bg-[var(--color-neo-blue)] text-white",
  OPEN: "bg-[var(--color-neo-yellow)] text-black",
  UNKNOWN: "bg-[var(--color-neo-yellow)] text-black",
  OVERDUE: "bg-[var(--color-neo-orange)] text-black",
  FAILED: "bg-[var(--color-neo-red)] text-white",
  DEFAULTED: "bg-[var(--color-neo-red)] text-white",
  BLOCK: "bg-[var(--color-neo-red)] text-white",
  FAIL: "bg-[var(--color-neo-red)] text-white",
  DOWN: "bg-[var(--color-neo-red)] text-white",
  REVERSED: "bg-white text-black",
  SKIPPED: "bg-white text-black",
  inactive: "bg-[var(--color-neo-green)] text-black",
  pending: "bg-[var(--color-neo-yellow)] text-black",
  firing: "bg-[var(--color-neo-red)] text-white",
};

// A glyph beside the text so the state is never carried by colour alone.
const ICONS: Record<string, string> = {
  CONFIRMED: "✓", DISBURSED: "✓", REPAID: "✓", RESOLVED: "✓", ALLOW: "✓", PASS: "✓", UP: "✓", inactive: "✓",
  PENDING: "…", ISSUED: "…", OPEN: "!", UNKNOWN: "?", pending: "…",
  FINANCED: "→", OVERDUE: "⏰",
  FAILED: "✗", DEFAULTED: "✗", BLOCK: "✗", FAIL: "✗", DOWN: "✗", firing: "▲",
  REVERSED: "↩", SKIPPED: "–",
};

export function StatusBadge({ status }: { status: string }) {
  const classes = COLORS[status] ?? "bg-white text-black";
  const icon = ICONS[status];
  return (
    <span className={`neo-badge ${classes}`}>
      {icon && <span aria-hidden className="mr-1">{icon}</span>}
      {status}
    </span>
  );
}
