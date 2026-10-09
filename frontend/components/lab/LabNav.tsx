"use client";

import { usePathname } from "next/navigation";
import { NavChip } from "@/components/ui";

const LINKS: Array<[string, string]> = [
  ["/lab", "Home"],
  ["/lab/playground", "Playground"],
  ["/lab/load", "Load"],
  ["/lab/chaos", "Chaos"],
  ["/lab/reconciliation", "Reconciliation"],
  ["/lab/invoices", "Invoices"],
  ["/lab/access", "Access"],
  ["/lab/logs", "Logs"],
  ["/lab/metrics", "Metrics"],
  ["/lab/events", "Events & Kafka"],
  ["/lab/traces", "Traces"],
  ["/lab/alerts", "Alerts"],
  ["/lab/audit", "Audit"],
  ["/lab/data", "Data"],
  ["/lab/correlate", "Correlate"],
  ["/lab/api", "API"],
];

export function LabNav() {
  const pathname = usePathname();
  return (
    <nav aria-label="Lab" className="mt-3 max-w-full overflow-x-auto pb-2">
      <ul className="flex w-max gap-2">
        {LINKS.map(([href, label]) => (
          <li key={href}>
            <NavChip href={href} active={pathname === href}>{label}</NavChip>
          </li>
        ))}
      </ul>
    </nav>
  );
}
