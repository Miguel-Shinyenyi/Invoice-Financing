import Link from "next/link";
import { apiFetch, requireUser } from "@/lib/api";
import type { LedgerMismatch, ReconciliationMismatch } from "@/lib/types";
import { StatusBadge } from "@/components/StatusBadge";
import { ResolveMismatchForm } from "@/components/ResolveMismatchForm";
import { TriggerRunButton } from "@/components/TriggerRunButton";
import { PageContainer } from "@/components/DetailList";
import { Notice, PageHeader, Table } from "@/components/ui";

export default async function ReconciliationPage() {
  const user = await requireUser();
  if (user.role !== "ADMIN" && user.role !== "SUPPORT") {
    return (
      <PageContainer narrow>
        <p className="font-semibold">You don&apos;t have access to reconciliation.</p>
      </PageContainer>
    );
  }

  const [mismatches, ledgerMismatches] = await Promise.all([
    apiFetch<ReconciliationMismatch[]>("/reconciliation/mismatches"),
    apiFetch<LedgerMismatch[]>("/reconciliation/ledger-mismatches"),
  ]);

  return (
    <PageContainer>
      <PageHeader title="Reconciliation" subtitle="Open items needing a human decision. Resolving records a reason and an audit entry; it does not change the settlement or the balance.">
        <TriggerRunButton />
      </PageHeader>

      <h2 className="mb-2 text-sm font-semibold">Settlement mismatches</h2>
      <Table head={["Settlement", "Internal", "External", "Details", "Found", "Resolve"]} empty={mismatches.length === 0 ? "No open mismatches." : undefined}>
        {mismatches.map((m) => (
          <tr key={m.id}>
            <td className="px-3 py-2"><Link href={`/settlements/${m.settlementId}`} className="font-mono text-[13px] text-link hover:underline">{m.settlementId.slice(0, 8)}</Link></td>
            <td className="px-3 py-2"><StatusBadge status={m.internalState} /></td>
            <td className="px-3 py-2"><StatusBadge status={m.externalState ?? "UNKNOWN"} /></td>
            <td className="min-w-56 px-3 py-2 text-xs">{m.details}</td>
            <td className="whitespace-nowrap px-3 py-2">{new Date(m.createdAt).toLocaleString()}</td>
            <td className="min-w-64 px-3 py-2"><ResolveMismatchForm mismatchId={m.id} /></td>
          </tr>
        ))}
      </Table>

      <h2 className="mb-2 mt-8 text-sm font-semibold">Ledger mismatches</h2>
      <div className="mb-3">
        <Notice tone="yellow" title="Found when an account is read.">
          An account whose stored balance disagrees with the net of its ledger entries. Resolving a row does not correct the balance: until the data is fixed by hand, the next read of that account opens a new row.
        </Notice>
      </div>
      <Table head={["Account", "Stored", "Computed", "Found", "Resolve"]} empty={ledgerMismatches.length === 0 ? "No open ledger mismatches." : undefined}>
        {ledgerMismatches.map((m) => (
          <tr key={m.id}>
            <td className="px-3 py-2"><Link href={`/accounts/${m.accountId}`} className="font-mono text-[13px] text-link hover:underline">{m.accountId.slice(0, 8)}</Link></td>
            <td className="px-3 py-2 font-mono">{m.storedBalance}</td>
            <td className="px-3 py-2 font-mono">{m.computedBalance}</td>
            <td className="whitespace-nowrap px-3 py-2">{new Date(m.createdAt).toLocaleString()}</td>
            <td className="min-w-64 px-3 py-2"><ResolveMismatchForm mismatchId={m.id} kind="ledger-mismatches" /></td>
          </tr>
        ))}
      </Table>
    </PageContainer>
  );
}
