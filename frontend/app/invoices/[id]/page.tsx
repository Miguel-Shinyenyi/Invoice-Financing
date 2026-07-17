import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { Invoice } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { FinanceButton } from "@/components/FinanceButton";

export default async function InvoiceDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const user = await requireUser();
  const { id } = await params;
  const invoice = await apiFetch<Invoice>(`/invoices/${id}`);

  const canFinance = invoice.status === "ISSUED" && (user.role === "ADMIN" || user.role === "SUPPORT");

  return (
    <div className="mx-auto max-w-2xl px-6 py-8">
      <Link href="/invoices" className="text-sm text-slate-500 hover:underline">
        &larr; Invoices
      </Link>
      <h1 className="mt-2 mb-6 font-mono text-lg text-slate-900">{invoice.id}</h1>

      <dl className="grid grid-cols-2 gap-4 rounded-lg border border-slate-200 bg-white p-6">
        <div>
          <dt className="text-xs uppercase text-slate-500">Status</dt>
          <dd className="mt-1">
            <StatusBadge status={invoice.status} />
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Amount</dt>
          <dd className="mt-1 text-slate-900">
            {invoice.amount} {invoice.currency}
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Customer reference</dt>
          <dd className="mt-1 text-slate-900">{invoice.customerReference}</dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Business account</dt>
          <dd className="mt-1">
            <Link href={`/accounts/${invoice.businessAccountId}`} className="font-mono text-xs text-blue-700 hover:underline">
              {invoice.businessAccountId}
            </Link>
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Due date</dt>
          <dd className="mt-1 text-slate-900">{new Date(invoice.dueDate).toLocaleDateString()}</dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">External source ref</dt>
          <dd className="mt-1 text-slate-900">{invoice.externalSourceRef ?? "—"}</dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Created</dt>
          <dd className="mt-1 text-slate-900">{new Date(invoice.createdAt).toLocaleString()}</dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Updated</dt>
          <dd className="mt-1 text-slate-900">{new Date(invoice.updatedAt).toLocaleString()}</dd>
        </div>
      </dl>

      {invoice.advance && (
        <>
          <h2 className="mt-8 mb-3 text-sm font-semibold uppercase text-slate-500">Advance</h2>
          <dl className="grid grid-cols-2 gap-4 rounded-lg border border-slate-200 bg-white p-6">
            <div>
              <dt className="text-xs uppercase text-slate-500">Status</dt>
              <dd className="mt-1">
                <StatusBadge status={invoice.advance.status} />
              </dd>
            </div>
            <div>
              <dt className="text-xs uppercase text-slate-500">Amount advanced</dt>
              <dd className="mt-1 text-slate-900">{invoice.advance.amountAdvanced}</dd>
            </div>
            <div>
              <dt className="text-xs uppercase text-slate-500">Fee</dt>
              <dd className="mt-1 text-slate-900">{invoice.advance.fee}</dd>
            </div>
            <div>
              <dt className="text-xs uppercase text-slate-500">Disbursed settlement</dt>
              <dd className="mt-1">
                <Link
                  href={`/settlements/${invoice.advance.disbursedSettlementId}`}
                  className="font-mono text-xs text-blue-700 hover:underline"
                >
                  {invoice.advance.disbursedSettlementId}
                </Link>
              </dd>
            </div>
            {invoice.advance.repaidSettlementId && (
              <div>
                <dt className="text-xs uppercase text-slate-500">Repaid settlement</dt>
                <dd className="mt-1">
                  <Link
                    href={`/settlements/${invoice.advance.repaidSettlementId}`}
                    className="font-mono text-xs text-blue-700 hover:underline"
                  >
                    {invoice.advance.repaidSettlementId}
                  </Link>
                </dd>
              </div>
            )}
          </dl>
        </>
      )}

      {canFinance && (
        <div className="mt-8">
          <FinanceButton invoiceId={invoice.id} />
        </div>
      )}
    </div>
  );
}
