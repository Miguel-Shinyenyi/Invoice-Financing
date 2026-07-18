import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { Page, SettlementSummary } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { Pagination } from "@/components/Pagination";

const STATUSES = ["PENDING", "CONFIRMED", "FAILED", "UNKNOWN", "REVERSED"];

export default async function SettlementsPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string; page?: string }>;
}) {
  await requireUser();
  const { status, page: pageParam } = await searchParams;
  const page = Number(pageParam ?? "0");

  const query = new URLSearchParams();
  if (status) query.set("status", status);
  query.set("page", String(page));
  query.set("size", "20");
  query.set("sort", "updatedAt,desc");

  const data = await apiFetch<Page<SettlementSummary>>(`/settlements?${query.toString()}`);

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-black uppercase tracking-tight text-black">Settlements</h1>
        <div className="flex flex-wrap gap-2">
          <Link href="/settlements" className={`neo-chip ${!status ? "bg-[var(--color-neo-yellow)]" : "bg-white"}`}>
            All
          </Link>
          {STATUSES.map((s) => (
            <Link
              key={s}
              href={`/settlements?status=${s}`}
              className={`neo-chip ${status === s ? "bg-[var(--color-neo-yellow)]" : "bg-white"}`}
            >
              {s}
            </Link>
          ))}
        </div>
      </div>

      <div className="neo-card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="border-b-2 border-black text-left text-xs font-black uppercase tracking-wide text-black">
            <tr>
              <th className="px-4 py-3">Settlement</th>
              <th className="px-4 py-3">Source</th>
              <th className="px-4 py-3">Destination</th>
              <th className="px-4 py-3">Amount</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Updated</th>
            </tr>
          </thead>
          <tbody className="divide-y-2 divide-black">
            {data.content.map((settlement) => (
              <tr key={settlement.settlementId} className="hover:bg-yellow-50">
                <td className="px-4 py-3">
                  <Link href={`/settlements/${settlement.settlementId}`} className="font-mono text-xs font-bold text-black hover:underline">
                    {settlement.settlementId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 font-mono text-xs font-bold text-black">
                  <Link href={`/accounts/${settlement.sourceAccountId}`} className="hover:underline">
                    {settlement.sourceAccountId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 font-mono text-xs font-bold text-black">
                  <Link href={`/accounts/${settlement.destinationAccountId}`} className="hover:underline">
                    {settlement.destinationAccountId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 font-bold text-black">
                  {settlement.amount} {settlement.currency}
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={settlement.status} />
                </td>
                <td className="px-4 py-3 font-medium text-black">{new Date(settlement.updatedAt).toLocaleString()}</td>
              </tr>
            ))}
            {data.content.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center font-bold text-black">
                  No settlements found.
                </td>
              </tr>
            )}
          </tbody>
        </table>
        <Pagination basePath="/settlements" page={data.page.number} totalPages={data.page.totalPages} extraParams={{ status }} />
      </div>
    </div>
  );
}
