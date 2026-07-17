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
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-slate-900">Invoices</h1>
        <div className="flex gap-2">
          <Link
            href="/invoices"
            className={`rounded-md px-3 py-1 text-sm ${!status ? "bg-slate-900 text-white" : "bg-white text-slate-600 border border-slate-300"}`}
          >
            All
          </Link>
          {STATUSES.map((s) => (
            <Link
              key={s}
              href={`/invoices?status=${s}`}
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
              <th className="px-4 py-3">Invoice</th>
              <th className="px-4 py-3">Customer ref</th>
              <th className="px-4 py-3">Business account</th>
              <th className="px-4 py-3">Amount</th>
              <th className="px-4 py-3">Due</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Updated</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {data.content.map((invoice) => (
              <tr key={invoice.id} className="hover:bg-slate-50">
                <td className="px-4 py-3">
                  <Link href={`/invoices/${invoice.id}`} className="font-mono text-xs text-blue-700 hover:underline">
                    {invoice.id.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 text-slate-900">{invoice.customerReference}</td>
                <td className="px-4 py-3 font-mono text-xs text-slate-600">
                  <Link href={`/accounts/${invoice.businessAccountId}`} className="hover:underline">
                    {invoice.businessAccountId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3 text-slate-900">
                  {invoice.amount} {invoice.currency}
                </td>
                <td className="px-4 py-3 text-slate-500">{new Date(invoice.dueDate).toLocaleDateString()}</td>
                <td className="px-4 py-3">
                  <StatusBadge status={invoice.status} />
                </td>
                <td className="px-4 py-3 text-slate-500">{new Date(invoice.updatedAt).toLocaleString()}</td>
              </tr>
            ))}
            {data.content.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-slate-400">
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
