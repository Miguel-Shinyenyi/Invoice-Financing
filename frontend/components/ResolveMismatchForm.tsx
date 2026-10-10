"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { BASE_PATH } from "@/lib/basePath";

export function ResolveMismatchForm({ mismatchId, kind = "mismatches" }: { mismatchId: string; kind?: "mismatches" | "ledger-mismatches" }) {
  const router = useRouter();
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function resolve() {
    if (!reason.trim()) {
      setError("A resolution reason is required.");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(`${BASE_PATH}/api/reconciliation/${kind}/${mismatchId}/resolve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reason }),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        setError(body.message ?? `Resolve failed (${response.status})`);
        return;
      }
      router.refresh();
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <input
        type="text"
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="Resolution reason"
        className="neo-input min-w-0 flex-1 text-sm"
      />
      <button onClick={resolve} disabled={loading} className="neo-btn tone-green">
        {loading ? "Resolving..." : "Resolve"}
      </button>
      {error && <span className="text-xs font-semibold text-[var(--red)]">{error}</span>}
    </div>
  );
}
