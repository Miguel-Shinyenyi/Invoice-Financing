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
      <Link href="/settlements" className="text-sm text-slate-500 hover:underline">
        &larr; Settlements
      </Link>
      <h1 className="mt-2 mb-6 font-mono text-lg text-slate-900">{settlement.settlementId}</h1>

      <dl className="grid grid-cols-2 gap-4 rounded-lg border border-slate-200 bg-white p-6">
        <div>
          <dt className="text-xs uppercase text-slate-500">Status</dt>
          <dd className="mt-1">
            <StatusBadge status={settlement.status} />
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Amount</dt>
          <dd className="mt-1 text-slate-900">
            {settlement.amount} {settlement.currency}
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Source account</dt>
          <dd className="mt-1">
            <Link href={`/accounts/${settlement.sourceAccountId}`} className="font-mono text-xs text-blue-700 hover:underline">
              {settlement.sourceAccountId}
            </Link>
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Destination account</dt>
          <dd className="mt-1">
            <Link href={`/accounts/${settlement.destinationAccountId}`} className="font-mono text-xs text-blue-700 hover:underline">
              {settlement.destinationAccountId}
            </Link>
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">External ref</dt>
          <dd className="mt-1 text-slate-900">{settlement.externalRef ?? "—"}</dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Created</dt>
          <dd className="mt-1 text-slate-900">{new Date(settlement.createdAt).toLocaleString()}</dd>
        </div>
        <div>
          <dt className="text-xs uppercase text-slate-500">Updated</dt>
          <dd className="mt-1 text-slate-900">{new Date(settlement.updatedAt).toLocaleString()}</dd>
        </div>
      </dl>
    </div>
  );
}
