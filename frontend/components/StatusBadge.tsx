const COLORS: Record<string, string> = {
  CONFIRMED: "bg-green-100 text-green-800",
  DISBURSED: "bg-green-100 text-green-800",
  REPAID: "bg-green-100 text-green-800",
  RESOLVED: "bg-green-100 text-green-800",
  ALLOW: "bg-green-100 text-green-800",
  PENDING: "bg-amber-100 text-amber-800",
  ISSUED: "bg-amber-100 text-amber-800",
  FINANCED: "bg-blue-100 text-blue-800",
  OPEN: "bg-amber-100 text-amber-800",
  UNKNOWN: "bg-amber-100 text-amber-800",
  OVERDUE: "bg-orange-100 text-orange-800",
  FAILED: "bg-red-100 text-red-800",
  DEFAULTED: "bg-red-100 text-red-800",
  BLOCK: "bg-red-100 text-red-800",
  REVERSED: "bg-slate-200 text-slate-700",
};

export function StatusBadge({ status }: { status: string }) {
  const classes = COLORS[status] ?? "bg-slate-100 text-slate-700";
  return <span className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${classes}`}>{status}</span>;
}
