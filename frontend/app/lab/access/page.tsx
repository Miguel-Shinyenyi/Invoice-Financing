"use client";

import { useCallback, useEffect, useState } from "react";
import { PersonaPicker, usePersona } from "@/components/lab/PersonaPicker";
import { ShowRequest } from "@/components/lab/ShowRequest";
import { Btn, Card, DocLink, Notice, PageHeader, Table } from "@/components/ui";
import { lab, type LabResult } from "@/lib/lab/client";
import { ACCOUNT_LABELS, ALICE, BOB_DRIFTED } from "@/lib/lab/seedLabels";
import type { Persona } from "@/lib/lab/types";
import { useLabJson } from "@/lib/lab/useLabJson";

interface Probe {
  label: string;
  path: string;
  expectation: string;
}

const PROBES: Probe[] = [
  { label: "List settlements", path: "settlements", expectation: "READ_ONLY sees only rows touching its own accounts" },
  { label: "Read its own account (Alice checking)", path: `accounts/${ALICE}`, expectation: "200 for everyone" },
  { label: "Read someone else's account (Bob operating)", path: "accounts/50000000-0000-0000-0000-000000000001", expectation: "READ_ONLY: 403" },
  { label: "Read someone else's INCONSISTENT account (Bob reserve)", path: `accounts/${BOB_DRIFTED}`, expectation: "READ_ONLY: 500, not 403 (the ledger check runs before the ownership check)" },
  { label: "Open reconciliation mismatches", path: "reconciliation/mismatches", expectation: "ADMIN/SUPPORT: 200, READ_ONLY: 403" },
  { label: "Open ledger mismatches", path: "reconciliation/ledger-mismatches", expectation: "ADMIN/SUPPORT: 200, READ_ONLY: 403" },
  { label: "List invoices", path: "invoices", expectation: "everyone: 200, READ_ONLY filtered to its accounts" },
];

type Outcome = LabResult<{ content?: unknown[]; message?: string }>;

export default function AccessPage() {
  const persona = usePersona();
  const { data: personas } = useLabJson<Persona[]>("personas", undefined, 60000);
  const [results, setResults] = useState<Record<string, Outcome>>({});
  const [busy, setBusy] = useState(false);
  const [create, setCreate] = useState<LabResult<unknown> | null>(null);

  const probeAll = useCallback(async () => {
    setBusy(true);
    const next: Record<string, Outcome> = {};
    for (const p of PROBES) next[p.path] = await lab.realGet(p.path, p.path === "settlements" || p.path === "invoices" ? { size: 100 } : undefined);
    setResults(next);
    setBusy(false);
  }, []);

  const role = persona.role;
  useEffect(() => {
    if (!role) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void probeAll();
  }, [role, probeAll]);

  async function tryCreate() {
    setCreate(await lab.realPost("settlements", { sourceAccountId: "10000000-0000-0000-0000-000000000001", destinationAccountId: "10000000-0000-0000-0000-000000000002", amount: 1, currency: "USD" }, { "Idempotency-Key": crypto.randomUUID() }));
  }

  const me = personas?.find((p) => p.role === role);
  return (
    <div className="space-y-5">
      <PageHeader title="Access" subtitle="Pick a persona. The calls below go to the real endpoints with that persona's real JWT, so the 200s, the 403s and the row-level filtering are genuine." />
      <Card title="Persona"><PersonaPicker role={role} onChoose={persona.choose} />
        {me && <p className="mt-2 text-xs font-medium">Sandbox user <code className="font-mono font-semibold">{me.username}</code> (password <code className="font-mono">{me.sandboxPassword}</code>, sandbox only, exists nowhere else). Owns {me.ownedAccounts.length} account(s){me.ownerId ? ` via owner_id ${me.ownerId.slice(0, 8)}…` : ""}.</p>}
      </Card>

      {!role && <Notice tone="yellow">Choose a persona to run the probes.</Notice>}
      {role && (
        <Card title={`What ${role} can call and see`} right={<Btn busy={busy} onClick={probeAll}>Run again</Btn>}>
          <Table head={["Call", "HTTP", "What came back", "Expected"]}>
            {PROBES.map((p) => {
              const r = results[p.path];
              const content = r?.data?.content;
              const summary = !r ? "…" : Array.isArray(content) ? `${content.length} row(s)` : r.ok ? "ok" : (r.data?.message ?? r.message ?? "");
              return (
                <tr key={p.path} data-probe={p.path} data-status={r?.status ?? ""}>
                  <td className="px-3 py-1.5 text-xs font-semibold">{p.label}<div className="font-mono text-[10px] font-medium">GET /{p.path}</div></td>
                  <td className="px-3 py-1.5"><span className={`neo-badge ${r && r.status >= 500 ? "tone-red" : r && r.status >= 400 ? "tone-orange" : "tone-green"}`}>{r ? `${r.ok ? "✓" : "✗"} ${r.status}` : "…"}</span></td>
                  <td className="max-w-80 px-3 py-1.5 text-xs">{summary}</td>
                  <td className="px-3 py-1.5 text-xs">{p.expectation}</td>
                </tr>
              );
            })}
          </Table>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Btn onClick={tryCreate}>Try POST /settlements</Btn>
            {create && <span className="text-sm font-semibold">HTTP {create.status}{create.status === 403 ? ": only ADMIN and SUPPORT may create settlements" : create.ok ? ": created" : ""}</span>}
          </div>
          <div className="mt-2"><ShowRequest sent={create?.sent ?? null} /></div>
          <div className="mt-3">
            <Notice tone="orange" title="The 500 is real.">
              On <code className="font-mono">GET /accounts/{"{id}"}</code> the ledger check runs before the ownership check, so a READ_ONLY user reading someone else&apos;s inconsistent account gets a 500, not a 403. This is recorded in <DocLink path="docs/security.md" anchor="current-state">security.md</DocLink> and shown as it is.
            </Notice>
          </div>
        </Card>
      )}
      <Card title="Names of the seed accounts">
        <ul className="grid gap-1 text-xs font-medium sm:grid-cols-2">{Object.entries(ACCOUNT_LABELS).map(([id, label]) => (<li key={id}><span className="font-semibold">{label}</span> <span className="font-mono text-muted">{id.slice(0, 8)}…</span></li>))}</ul>
      </Card>
    </div>
  );
}
