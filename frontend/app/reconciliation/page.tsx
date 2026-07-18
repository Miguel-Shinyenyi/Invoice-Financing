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
        <p className="font-bold text-black">You don&apos;t have access to reconciliation.</p>
      </div>
    );
  }

  const mismatches = await apiFetch<ReconciliationMismatch[]>("/reconciliation/mismatches");

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-black uppercase tracking-tight text-black">Reconciliation</h1>
        <TriggerRunButton />
      </div>

      <div className="neo-card overflow-hidden">
        <table className="w-full text-sm">
          <thead className="border-b-2 border-black text-left text-xs font-black uppercase tracking-wide text-black">
            <tr>
              <th className="px-4 py-3">Settlement</th>
              <th className="px-4 py-3">Internal state</th>
              <th className="px-4 py-3">External state</th>
              <th className="px-4 py-3">Details</th>
              <th className="px-4 py-3">Found</th>
              <th className="px-4 py-3">Resolve</th>
            </tr>
          </thead>
          <tbody className="divide-y-2 divide-black">
            {mismatches.map((mismatch) => (
              <tr key={mismatch.id} className="hover:bg-yellow-50">
                <td className="px-4 py-3">
                  <Link href={`/settlements/${mismatch.settlementId}`} className="font-mono text-xs font-bold text-black hover:underline">
                    {mismatch.settlementId.slice(0, 8)}
                  </Link>
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={mismatch.internalState} />
                </td>
                <td className="px-4 py-3">
                  <StatusBadge status={mismatch.externalState ?? "UNKNOWN"} />
                </td>
                <td className="max-w-xs truncate px-4 py-3 font-medium text-black" title={mismatch.details}>
                  {mismatch.details}
                </td>
                <td className="px-4 py-3 font-medium text-black">{new Date(mismatch.createdAt).toLocaleString()}</td>
                <td className="px-4 py-3">
                  <ResolveMismatchForm mismatchId={mismatch.id} />
                </td>
              </tr>
            ))}
            {mismatches.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center font-bold text-black">
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
