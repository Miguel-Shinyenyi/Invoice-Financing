import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { InvoiceSummary, Page } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { Pagination } from "@/components/Pagination";

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
    <div className="mx-auto max-w-6xl px-6 py-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-black uppercase tracking-tight text-black">Invoices</h1>
        <div className="flex flex-wrap gap-2">
          <Link href="/invoices" className={`neo-chip ${!status ? "bg-[var(--color-neo-yellow)]" : "bg-white"}`}>
            All
          </Link>
          {STATUSES.map((s) => (
            <Link
              key={s}
              href={`/invoices?status=${s}`}
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
              <th className="px-4 py-3">Invoice</th>
              <th className="px-4 py-3">Customer ref</th>
              <th className="px-4 py-3">Business account</th>
              <th className="px-4 py-3">Amount</th>
              <th className="px-4 py-3">Due</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Updated</th>
            </tr>
          </thead>
          <tbody className="divide-y-2 divide-black">
            {data.content.map((invoice) => (
              <tr key={invoice.id} className="hover:bg-yellow-50">
                <td className="px-4 py-3">
                  <Link href={`/invoices/${invoice.id}`} className="font-mono text-xs font-bold text-black hover:underline">
                    {invoice.id.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 font-bold text-black">{invoice.customerReference}</td>
                <td className="px-4 py-3 font-mono text-xs font-bold text-black">
                  <Link href={`/accounts/${invoice.businessAccountId}`} className="hover:underline">
                    {invoice.businessAccountId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 font-bold text-black">
                  {invoice.amount} {invoice.currency}
                </td>
                <td className="px-4 py-3 font-medium text-black">{new Date(invoice.dueDate).toLocaleDateString()}</td>
                <td className="px-4 py-3">
                  <StatusBadge status={invoice.status} />
                </td>
                <td className="px-4 py-3 font-medium text-black">{new Date(invoice.updatedAt).toLocaleString()}</td>
              </tr>
            ))}
            {data.content.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center font-bold text-black">
                  No invoices found.
                </td>
              </tr>
            )}
          </tbody>
        </table>
        <Pagination basePath="/invoices" page={data.page.number} totalPages={data.page.totalPages} extraParams={{ status }} />
      </div>
    </div>
  );
}
