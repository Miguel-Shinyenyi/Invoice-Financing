import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { ReconciliationMismatch } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { ResolveMismatchForm } from "@/components/ResolveMismatchForm";
import { TriggerRunButton } from "@/components/TriggerRunButton";

export default async function ReconciliationPage() {
  const user = await requireUser();
  if (user.role !== "ADMIN" && user.role !== "SUPPORT") {
    return (
      <div className="mx-auto max-w-4xl px-6 py-8">
        <p className="text-slate-500">You don&apos;t have access to reconciliation.</p>
      </div>
    );
  }

  const mismatches = await apiFetch<ReconciliationMismatch[]>("/reconciliation/mismatches");

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-slate-900">Reconciliation</h1>
        <TriggerRunButton />
      </div>

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-3">Settlement</th>
              <th className="px-4 py-3">Internal state</th>
              <th className="px-4 py-3">External state</th>
              <th className="px-4 py-3">Details</th>
              <th className="px-4 py-3">Found</th>
              <th className="px-4 py-3">Resolve</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {mismatches.map((mismatch) => (
              <tr key={mismatch.id} className="hover:bg-slate-50">
                <td className="px-4 py-3">
                  <Link href={`/settlements/${mismatch.settlementId}`} className="font-mono text-xs text-blue-700 hover:underline">
                    {mismatch.settlementId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={mismatch.internalState} />
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={mismatch.externalState ?? "UNKNOWN"} />
                </td>
                <td className="px-4 py-3 max-w-xs truncate text-slate-600" title={mismatch.details}>
                  {mismatch.details}
                </td>
                <td className="px-4 py-3 text-slate-500">{new Date(mismatch.createdAt).toLocaleString()}</td>
                <td className="px-4 py-3">
                  <ResolveMismatchForm mismatchId={mismatch.id} />
                </td>
              </tr>
            ))}
            {mismatches.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-slate-400">
                  No open mismatches.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
