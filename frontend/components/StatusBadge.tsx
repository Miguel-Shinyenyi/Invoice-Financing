const COLORS: Record<string, string> = {
  CONFIRMED: "bg-[var(--color-neo-green)] text-white",
  DISBURSED: "bg-[var(--color-neo-green)] text-white",
  REPAID: "bg-[var(--color-neo-green)] text-white",
  RESOLVED: "bg-[var(--color-neo-green)] text-white",
  ALLOW: "bg-[var(--color-neo-green)] text-white",
  PENDING: "bg-[var(--color-neo-yellow)] text-black",
  ISSUED: "bg-[var(--color-neo-yellow)] text-black",
  FINANCED: "bg-[var(--color-neo-blue)] text-white",
  OPEN: "bg-[var(--color-neo-yellow)] text-black",
  UNKNOWN: "bg-[var(--color-neo-yellow)] text-black",
  OVERDUE: "bg-[var(--color-neo-orange)] text-black",
  FAILED: "bg-[var(--color-neo-red)] text-white",
  DEFAULTED: "bg-[var(--color-neo-red)] text-white",
  BLOCK: "bg-[var(--color-neo-red)] text-white",
  REVERSED: "bg-white text-black",
};

export function StatusBadge({ status }: { status: string }) {
  const classes = COLORS[status] ?? "bg-white text-black";
  return <span className={`neo-badge ${classes}`}>{status}</span>;
}
