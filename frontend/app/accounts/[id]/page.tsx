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
      <h1 className="mb-6 font-mono text-lg font-bold text-black">{account.id}</h1>

      <dl className="neo-card mb-8 grid grid-cols-3 gap-4 p-6">
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Balance</dt>
          <dd className="mt-1 text-2xl font-black text-black">
            {account.balance} {account.currency}
          </dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Owner</dt>
          <dd className="mt-1 font-mono text-xs font-bold text-black">{account.ownerId}</dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Created</dt>
          <dd className="mt-1 font-medium text-black">{new Date(account.createdAt).toLocaleString()}</dd>
        </div>
      </dl>

      <h2 className="mb-3 text-sm font-black uppercase tracking-wide text-black">Settlement history</h2>
      <div className="neo-card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="border-b-2 border-black text-left text-xs font-black uppercase tracking-wide text-black">
            <tr>
              <th className="px-4 py-3">Settlement</th>
              <th className="px-4 py-3">Direction</th>
              <th className="px-4 py-3">Amount</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Updated</th>
            </tr>
          </thead>
          <tbody className="divide-y-2 divide-black">
            {history.content.map((settlement) => (
              <tr key={settlement.settlementId} className="hover:bg-yellow-50">
                <td className="px-4 py-3">
                  <Link href={`/settlements/${settlement.settlementId}`} className="font-mono text-xs font-bold text-black hover:underline">
                    {settlement.settlementId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 font-bold text-black">
                  {settlement.sourceAccountId === account.id ? "Outgoing" : "Incoming"}
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
            {history.content.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center font-bold text-black">
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
