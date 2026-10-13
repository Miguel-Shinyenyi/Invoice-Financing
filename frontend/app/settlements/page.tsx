import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { Page, SettlementSummary } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { Pagination } from "@/components/Pagination";
import { PageContainer } from "@/components/DetailList";
import { NavChip, PageHeader, Table } from "@/components/ui";

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
    <PageContainer>
      <PageHeader title="Settlements" subtitle="From the read model: the CQRS projection of the settlements table.">
        <div className="segmented max-w-full overflow-x-auto" role="group" aria-label="Filter by status">
          <NavChip href="/settlements" active={!status}>All</NavChip>
          {STATUSES.map((s) => (
            <NavChip key={s} href={`/settlements?status=${s}`} active={status === s}>{s}</NavChip>
          ))}
        </div>
      </PageHeader>

      <Table head={["Settlement", "Source", "Destination", "Amount", "Status", "Updated"]} empty={data.content.length === 0 ? "No settlements found." : undefined}>
        {data.content.map((s) => (
          <tr key={s.settlementId}>
            <td className="px-3 py-2"><Link href={`/settlements/${s.settlementId}`} className="font-mono text-[13px] text-link hover:underline">{s.settlementId.slice(0, 8)}</Link></td>
            <td className="px-3 py-2"><Link href={`/accounts/${s.sourceAccountId}`} className="font-mono text-[13px] text-link hover:underline">{s.sourceAccountId.slice(0, 8)}</Link></td>
            <td className="px-3 py-2"><Link href={`/accounts/${s.destinationAccountId}`} className="font-mono text-[13px] text-link hover:underline">{s.destinationAccountId.slice(0, 8)}</Link></td>
            <td className="px-3 py-2 font-semibold">{s.amount} {s.currency}</td>
            <td className="px-3 py-2"><StatusBadge status={s.status} /></td>
            <td className="whitespace-nowrap px-3 py-2">{new Date(s.updatedAt).toLocaleString()}</td>
          </tr>
        ))}
      </Table>
      <div className="neo-card mt-4">
        <Pagination basePath="/settlements" page={data.page.number} totalPages={data.page.totalPages} extraParams={{ status }} />
      </div>
    </PageContainer>
  );
}
