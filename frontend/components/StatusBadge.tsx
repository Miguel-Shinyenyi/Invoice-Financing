const COLORS: Record<string, string> = {
  CONFIRMED: "tone-green",
  DISBURSED: "tone-green",
  REPAID: "tone-green",
  RESOLVED: "tone-green",
  ALLOW: "tone-green",
  PASS: "tone-green",
  UP: "tone-green",
  PENDING: "tone-yellow",
  ISSUED: "tone-yellow",
  FINANCED: "tone-blue",
  OPEN: "tone-yellow",
  UNKNOWN: "tone-yellow",
  OVERDUE: "tone-orange",
  FAILED: "tone-red",
  DEFAULTED: "tone-red",
  BLOCK: "tone-red",
  FAIL: "tone-red",
  DOWN: "tone-red",
  REVERSED: "tone-default",
  SKIPPED: "tone-default",
  inactive: "tone-green",
  pending: "tone-yellow",
  firing: "tone-red",
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
  const classes = COLORS[status] ?? "tone-default";
  const icon = ICONS[status];
  return (
    <span className={`neo-badge ${classes}`}>
      {icon && <span aria-hidden className="mr-1">{icon}</span>}
      {status}
    </span>
  );
}
