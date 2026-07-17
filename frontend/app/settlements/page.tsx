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
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-slate-900">Settlements</h1>
        <div className="flex gap-2">
          <Link
            href="/settlements"
            className={`rounded-md px-3 py-1 text-sm ${!status ? "bg-slate-900 text-white" : "bg-white text-slate-600 border border-slate-300"}`}
          >
            All
          </Link>
          {STATUSES.map((s) => (
            <Link
              key={s}
              href={`/settlements?status=${s}`}
              className={`rounded-md px-3 py-1 text-sm ${status === s ? "bg-slate-900 text-white" : "bg-white text-slate-600 border border-slate-300"}`}
            >
              {s}
            </Link>
          ))}
        </div>
      </div>

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-3">Settlement</th>
              <th className="px-4 py-3">Source</th>
              <th className="px-4 py-3">Destination</th>
              <th className="px-4 py-3">Amount</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Updated</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {data.content.map((settlement) => (
              <tr key={settlement.settlementId} className="hover:bg-slate-50">
                <td className="px-4 py-3">
                  <Link href={`/settlements/${settlement.settlementId}`} className="font-mono text-xs text-blue-700 hover:underline">
                    {settlement.settlementId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 font-mono text-xs text-slate-600">
                  <Link href={`/accounts/${settlement.sourceAccountId}`} className="hover:underline">
                    {settlement.sourceAccountId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 font-mono text-xs text-slate-600">
                  <Link href={`/accounts/${settlement.destinationAccountId}`} className="hover:underline">
                    {settlement.destinationAccountId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 text-slate-900">
                  {settlement.amount} {settlement.currency}
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={settlement.status} />
                </td>
                <td className="px-4 py-3 text-slate-500">{new Date(settlement.updatedAt).toLocaleString()}</td>
              </tr>
            ))}
            {data.content.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-slate-400">
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
