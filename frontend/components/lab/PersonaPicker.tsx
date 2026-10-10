"use client";

import { useCallback, useEffect, useState } from "react";
import { Btn } from "@/components/ui";
import { BASE_PATH } from "@/lib/basePath";
import { choosePersona } from "@/lib/lab/client";
import { LAB_ROLES } from "@/lib/lab/cookies";

/** Which sandbox user the real-endpoint calls act as. The token lives in an httpOnly cookie; this only shows the role. */
export function usePersona() {
  const [role, setRole] = useState<string | null>(null);
  const [ready, setReady] = useState(false);

  const refresh = useCallback(async () => {
    const res = await fetch(`${BASE_PATH}/api/lab-persona`, { cache: "no-store" }).catch(() => null);
    const body = res && res.ok ? ((await res.json()) as { role: string | null }) : { role: null };
    setRole(body.role);
    setReady(true);
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
  }, [refresh]);

  const choose = useCallback(async (r: string) => {
    const result = await choosePersona(r);
    if (result.ok) setRole(r);
    return result.ok;
  }, []);

  return { role, ready, choose };
}

export function PersonaPicker({ role, onChoose }: { role: string | null; onChoose: (r: string) => Promise<boolean> }) {
  const [busy, setBusy] = useState<string | null>(null);
  return (
    <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Acting as">
      <span className="text-xs font-semibold">Acting as</span>
      {LAB_ROLES.map((r) => (
        <Btn key={r} tone={role === r ? "yellow" : "default"} aria-pressed={role === r} busy={busy === r} onClick={async () => { setBusy(r); await onChoose(r); setBusy(null); }}>
          {role === r && <span aria-hidden className="mr-1">✓</span>}{r}
        </Btn>
      ))}
      {!role && <span className="text-xs font-medium">No persona yet: pick one to call the real endpoints.</span>}
    </div>
  );
}
