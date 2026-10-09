import Link from "next/link";
import { ApiError, apiFetch, requireUser } from "@/lib/api";
import type { Account, Page, SettlementSummary } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { Pagination } from "@/components/Pagination";
import { DetailList, PageContainer } from "@/components/DetailList";
import { Notice, PageHeader, Table } from "@/components/ui";

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

  let account: Account;
  try {
    account = await apiFetch<Account>(`/accounts/${id}`);
  } catch (e) {
    if (e instanceof ApiError && e.status === 500) {
      // LedgerInconsistencyException: the stored balance disagrees with the account's ledger entries.
      return (
        <PageContainer narrow>
          <PageHeader title="Account" subtitle={<span className="break-all font-mono">{id}</span>} />
          <Notice tone="red" title="This account's balance cannot be shown.">
            Its stored balance does not match the net of its ledger entries. A ledger mismatch has been recorded for manual review.{" "}
            <Link href="/reconciliation" className="font-bold underline">Open reconciliation</Link>.
          </Notice>
        </PageContainer>
      );
    }
    throw e;
  }
  const history = await apiFetch<Page<SettlementSummary>>(`/accounts/${id}/settlements?page=${page}&size=20&sort=updatedAt,desc`);

  return (
    <PageContainer>
      <PageHeader title="Account" subtitle={<span className="break-all font-mono">{account.id}</span>} />
      <DetailList
        columns={3}
        items={[
          ["Balance", <span key="b" className="text-2xl font-black">{account.balance} {account.currency}</span>],
          ["Owner", <span key="o" className="break-all font-mono text-xs font-bold">{account.ownerId}</span>],
          ["Created", new Date(account.createdAt).toLocaleString()],
        ]}
      />
      <h2 className="mb-3 mt-8 text-sm font-black uppercase tracking-wide">Settlement history</h2>
      <Table head={["Settlement", "Direction", "Amount", "Status", "Updated"]} empty={history.content.length === 0 ? "No settlements yet." : undefined}>
        {history.content.map((s) => (
          <tr key={s.settlementId}>
            <td className="px-3 py-2"><Link href={`/settlements/${s.settlementId}`} className="font-mono text-xs font-bold underline">{s.settlementId.slice(0, 8)}</Link></td>
            <td className="px-3 py-2 font-bold">{s.sourceAccountId === account.id ? "Outgoing" : "Incoming"}</td>
            <td className="px-3 py-2 font-bold">{s.amount} {s.currency}</td>
            <td className="px-3 py-2"><StatusBadge status={s.status} /></td>
            <td className="whitespace-nowrap px-3 py-2">{new Date(s.updatedAt).toLocaleString()}</td>
          </tr>
        ))}
      </Table>
      <div className="neo-card mt-4"><Pagination basePath={`/accounts/${id}`} page={history.page.number} totalPages={history.page.totalPages} /></div>
    </PageContainer>
  );
}
