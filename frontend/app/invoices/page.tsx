import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { InvoiceSummary, Page } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { Pagination } from "@/components/Pagination";
import { PageContainer } from "@/components/DetailList";
import { NavChip, PageHeader, Table } from "@/components/ui";

const STATUSES = ["ISSUED", "FINANCED", "REPAID", "OVERDUE"];

export default async function InvoicesPage({
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

  const data = await apiFetch<Page<InvoiceSummary>>(`/invoices?${query.toString()}`);

  return (
    <PageContainer>
      <PageHeader title="Invoices">
        <div className="flex max-w-full flex-wrap gap-2">
          <NavChip href="/invoices" active={!status}>All</NavChip>
          {STATUSES.map((s) => (
            <NavChip key={s} href={`/invoices?status=${s}`} active={status === s}>{s}</NavChip>
          ))}
        </div>
      </PageHeader>
      <Table head={["Invoice", "Customer reference", "Business", "Amount", "Due", "Status"]} empty={data.content.length === 0 ? "No invoices found." : undefined}>
        {data.content.map((i) => (
          <tr key={i.id}>
            <td className="px-3 py-2"><Link href={`/invoices/${i.id}`} className="font-mono text-xs font-bold underline">{i.id.slice(0, 8)}</Link></td>
            <td className="px-3 py-2">{i.customerReference}</td>
            <td className="px-3 py-2"><Link href={`/accounts/${i.businessAccountId}`} className="font-mono text-xs font-bold underline">{i.businessAccountId.slice(0, 8)}</Link></td>
            <td className="px-3 py-2 font-bold">{i.amount} {i.currency}</td>
            <td className="whitespace-nowrap px-3 py-2">{new Date(i.dueDate).toLocaleDateString()}</td>
            <td className="px-3 py-2"><StatusBadge status={i.status} /></td>
          </tr>
        ))}
      </Table>
      <div className="neo-card mt-4"><Pagination basePath="/invoices" page={data.page.number} totalPages={data.page.totalPages} extraParams={{ status }} /></div>
    </PageContainer>
  );
}
