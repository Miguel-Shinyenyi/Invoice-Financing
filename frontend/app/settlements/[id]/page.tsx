import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { Settlement } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";

export default async function SettlementDetailPage({ params }: { params: Promise<{ id: string }> }) {
  await requireUser();
  const { id } = await params;
  const settlement = await apiFetch<Settlement>(`/settlements/${id}`);

  return (
    <div className="mx-auto max-w-2xl px-6 py-8">
      <Link href="/settlements" className="text-sm font-bold text-black hover:underline">
        &larr; Settlements
      </Link>
      <h1 className="mt-2 mb-6 font-mono text-lg font-bold text-black">{settlement.settlementId}</h1>

      <dl className="neo-card grid grid-cols-2 gap-4 p-6">
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Status</dt>
          <dd className="mt-1">
            <StatusBadge status={settlement.status} />
          </dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Amount</dt>
          <dd className="mt-1 font-bold text-black">
            {settlement.amount} {settlement.currency}
          </dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Source account</dt>
          <dd className="mt-1">
            <Link href={`/accounts/${settlement.sourceAccountId}`} className="font-mono text-xs font-bold text-black hover:underline">
              {settlement.sourceAccountId}
            </Link>
          </dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Destination account</dt>
          <dd className="mt-1">
            <Link href={`/accounts/${settlement.destinationAccountId}`} className="font-mono text-xs font-bold text-black hover:underline">
              {settlement.destinationAccountId}
            </Link>
          </dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">External ref</dt>
          <dd className="mt-1 font-medium text-black">{settlement.externalRef ?? "—"}</dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Created</dt>
          <dd className="mt-1 font-medium text-black">{new Date(settlement.createdAt).toLocaleString()}</dd>
        </div>
        <div>
          <dt className="text-xs font-black uppercase tracking-wide text-black">Updated</dt>
          <dd className="mt-1 font-medium text-black">{new Date(settlement.updatedAt).toLocaleString()}</dd>
        </div>
      </dl>
    </div>
  );
}
