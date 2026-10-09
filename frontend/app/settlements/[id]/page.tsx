import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { Settlement } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { DetailList, PageContainer } from "@/components/DetailList";
import { PageHeader } from "@/components/ui";

export default async function SettlementDetailPage({ params }: { params: Promise<{ id: string }> }) {
  await requireUser();
  const { id } = await params;
  const s = await apiFetch<Settlement>(`/settlements/${id}`);

  return (
    <PageContainer narrow>
      <Link href="/settlements" className="text-sm font-bold hover:underline">&larr; Settlements</Link>
      <div className="mt-2"><PageHeader title="Settlement" subtitle={<span className="break-all font-mono">{s.settlementId}</span>} /></div>
      <DetailList
        items={[
          ["Status", <StatusBadge key="s" status={s.status} />],
          ["Amount", `${s.amount} ${s.currency}`],
          ["Source account", <Link key="a" href={`/accounts/${s.sourceAccountId}`} className="break-all font-mono text-xs font-bold underline">{s.sourceAccountId}</Link>],
          ["Destination account", <Link key="b" href={`/accounts/${s.destinationAccountId}`} className="break-all font-mono text-xs font-bold underline">{s.destinationAccountId}</Link>],
          ["External ref", s.externalRef ?? "—"],
          ["Created", new Date(s.createdAt).toLocaleString()],
          ["Updated", new Date(s.updatedAt).toLocaleString()],
        ]}
      />
    </PageContainer>
  );
}
