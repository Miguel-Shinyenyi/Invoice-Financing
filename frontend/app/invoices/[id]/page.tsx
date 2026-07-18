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
      <Link href="/invoices" className="text-sm font-bold text-black hover:underline">
        &larr; Invoices
      </Link>
      <h1 className="mt-2 mb-6 font-mono text-lg font-bold text-black">{invoice.id}</h1>

      <dl className="neo-card grid grid-cols-2 gap-4 p-6">
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Status</dt>
          <dd className="mt-1">
            <StatusBadge status={invoice.status} />
          </dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Amount</dt>
          <dd className="mt-1 font-bold text-black">
            {invoice.amount} {invoice.currency}
          </dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Customer reference</dt>
          <dd className="mt-1 font-medium text-black">{invoice.customerReference}</dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Business account</dt>
          <dd className="mt-1">
            <Link href={`/accounts/${invoice.businessAccountId}`} className="font-mono text-xs font-bold text-black hover:underline">
              {invoice.businessAccountId}
            </Link>
          </dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Due date</dt>
          <dd className="mt-1 font-medium text-black">{new Date(invoice.dueDate).toLocaleDateString()}</dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">External source ref</dt>
          <dd className="mt-1 font-medium text-black">{invoice.externalSourceRef ?? "—"}</dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Created</dt>
          <dd className="mt-1 font-medium text-black">{new Date(invoice.createdAt).toLocaleString()}</dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Updated</dt>
          <dd className="mt-1 font-medium text-black">{new Date(invoice.updatedAt).toLocaleString()}</dd>
        </div>
      </dl>

      {invoice.advance && (
        <>
          <h2 className="mt-8 mb-3 text-sm font-black uppercase tracking-wide text-black">Advance</h2>
          <dl className="neo-card grid grid-cols-2 gap-4 p-6">
            <div>
              <dt className="text-xs font-black uppercase tracking-wide text-black">Status</dt>
              <dd className="mt-1">
                <StatusBadge status={invoice.advance.status} />
              </dd>
            </div>
            <div>
              <dt className="text-xs font-black uppercase tracking-wide text-black">Amount advanced</dt>
              <dd className="mt-1 font-bold text-black">{invoice.advance.amountAdvanced}</dd>
            </div>
            <div>
              <dt className="text-xs font-black uppercase tracking-wide text-black">Fee</dt>
              <dd className="mt-1 font-bold text-black">{invoice.advance.fee}</dd>
            </div>
            <div>
              <dt className="text-xs font-black uppercase tracking-wide text-black">Disbursed settlement</dt>
              <dd className="mt-1">
                <Link
                  href={`/settlements/${invoice.advance.disbursedSettlementId}`}
                  className="font-mono text-xs font-bold text-black hover:underline"
                >
                  {invoice.advance.disbursedSettlementId}
                </Link>
              </dd>
            </div>
            {invoice.advance.repaidSettlementId && (
              <div>
                <dt className="text-xs font-black uppercase tracking-wide text-black">Repaid settlement</dt>
                <dd className="mt-1">
                  <Link
                    href={`/settlements/${invoice.advance.repaidSettlementId}`}
                    className="font-mono text-xs font-bold text-black hover:underline"
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
