import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { Invoice } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { FinanceButton } from "@/components/FinanceButton";
import { DetailList, PageContainer } from "@/components/DetailList";
import { PageHeader } from "@/components/ui";

export default async function InvoiceDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const user = await requireUser();
  const { id } = await params;
  const invoice = await apiFetch<Invoice>(`/invoices/${id}`);
  const canFinance = invoice.status === "ISSUED" && (user.role === "ADMIN" || user.role === "SUPPORT");
  const settlementLink = (sid: string) => (
    <Link key={sid} href={`/settlements/${sid}`} className="break-all font-mono text-xs font-bold underline">{sid}</Link>
  );

  return (
    <PageContainer narrow>
      <Link href="/invoices" className="text-sm font-bold hover:underline">&larr; Invoices</Link>
      <div className="mt-2"><PageHeader title="Invoice" subtitle={<span className="break-all font-mono">{invoice.id}</span>} /></div>
      <DetailList
        items={[
          ["Status", <StatusBadge key="s" status={invoice.status} />],
          ["Amount", `${invoice.amount} ${invoice.currency}`],
          ["Customer reference", invoice.customerReference],
          ["Business account", <Link key="b" href={`/accounts/${invoice.businessAccountId}`} className="break-all font-mono text-xs font-bold underline">{invoice.businessAccountId}</Link>],
          ["Due date", new Date(invoice.dueDate).toLocaleDateString()],
          ["External source ref", invoice.externalSourceRef ?? "—"],
          ["Created", new Date(invoice.createdAt).toLocaleString()],
          ["Updated", new Date(invoice.updatedAt).toLocaleString()],
        ]}
      />
      {invoice.advance && (
        <>
          <h2 className="mb-3 mt-8 text-sm font-black uppercase tracking-wide">Advance</h2>
          <DetailList
            items={[
              ["Status", <StatusBadge key="a" status={invoice.advance.status} />],
              ["Amount advanced", invoice.advance.amountAdvanced],
              ["Fee", invoice.advance.fee],
              ["Disbursed settlement", settlementLink(invoice.advance.disbursedSettlementId)],
              ...(invoice.advance.repaidSettlementId ? [["Repaid settlement", settlementLink(invoice.advance.repaidSettlementId)] as [string, React.ReactNode]] : []),
            ]}
          />
        </>
      )}
      {canFinance && <div className="mt-6"><FinanceButton invoiceId={invoice.id} /></div>}
    </PageContainer>
  );
}
