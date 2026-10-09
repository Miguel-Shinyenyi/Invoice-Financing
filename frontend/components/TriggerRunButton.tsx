"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { BASE_PATH } from "@/lib/basePath";

export function TriggerRunButton() {
  const router = useRouter();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function trigger() {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(`${BASE_PATH}/api/reconciliation/runs`, { method: "POST" });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        setError(body.message ?? `Run failed (${response.status})`);
        return;
      }
      router.refresh();
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex items-center gap-2">
      <button onClick={trigger} disabled={loading} className="neo-btn">
        {loading ? "Running..." : "Trigger reconciliation run"}
      </button>
      {error && <span className="text-xs font-bold text-[var(--color-neo-red)]">{error}</span>}
    </div>
  );
}
