import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { Account, Page, SettlementSummary } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { Pagination } from "@/components/Pagination";

export default async function AccountDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ page?: string }>;
}) {
  await requireUser();
  const { id } = await params;
  const { page: pageParam } = await searchParams;
  const page = Number(pageParam ?? "0");

  const account = await apiFetch<Account>(`/accounts/${id}`);
  const history = await apiFetch<Page<SettlementSummary>>(
    `/accounts/${id}/settlements?page=${page}&size=20&sort=updatedAt,desc`,
  );

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <h1 className="mb-6 font-mono text-lg text-slate-900">{account.id}</h1>

      <dl className="mb-8 grid grid-cols-3 gap-4 rounded-lg border border-slate-200 bg-white p-6">
        <div>
          <dt className="text-xs uppercase text-slate-500">Balance</dt>
          <dd className="mt-1 text-2xl font-semibold text-slate-900">
            {account.balance} {account.currency}
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Owner</dt>
          <dd className="mt-1 font-mono text-xs text-slate-600">{account.ownerId}</dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Created</dt>
          <dd className="mt-1 text-slate-900">{new Date(account.createdAt).toLocaleString()}</dd>
        </div>
      </dl>

      <h2 className="mb-3 text-sm font-semibold uppercase text-slate-500">Settlement history</h2>
      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-3">Settlement</th>
              <th className="px-4 py-3">Direction</th>
              <th className="px-4 py-3">Amount</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Updated</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {history.content.map((settlement) => (
              <tr key={settlement.settlementId} className="hover:bg-slate-50">
                <td className="px-4 py-3">
                  <Link href={`/settlements/${settlement.settlementId}`} className="font-mono text-xs text-blue-700 hover:underline">
                    {settlement.settlementId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 text-slate-600">
                  {settlement.sourceAccountId === account.id ? "Outgoing" : "Incoming"}
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
            {history.content.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-slate-400">
                  No settlements yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
        <Pagination basePath={`/accounts/${id}`} page={history.page.number} totalPages={history.page.totalPages} />
      </div>
    </div>
  );
}
