"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { BASE_PATH } from "@/lib/basePath";

export function ResolveMismatchForm({ mismatchId }: { mismatchId: string }) {
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
      const response = await fetch(`${BASE_PATH}/api/reconciliation/mismatches/${mismatchId}/resolve`, {
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
    <div className="flex items-center gap-2">
      <input
        type="text"
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="Resolution reason"
        className="neo-input text-sm"
      />
      <button onClick={resolve} disabled={loading} className="neo-btn bg-[var(--color-neo-green)] text-white">
        {loading ? "Resolving..." : "Resolve"}
      </button>
      {error && <span className="text-xs font-bold text-[var(--color-neo-red)]">{error}</span>}
    </div>
  );
}
