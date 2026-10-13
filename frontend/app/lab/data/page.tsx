"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { Btn, Card, LiveBadge, PageHeader, Table } from "@/components/ui";
import { useLabJson } from "@/lib/lab/useLabJson";

interface DataView { table: string; columns: string[]; rows: Array<Record<string, unknown>>; page: number; pageSize: number; total: number; tables: string[] }

function cell(v: unknown): string {
  if (v === null || v === undefined) return "∅";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

export default function DataPage() {
  const initial = useSearchParams().get("table") ?? "settlements";
  const [table, setTable] = useState(initial);
  const [page, setPage] = useState(0);
  const { data, last, mode } = useLabJson<DataView>(`data/${table}`, { page }, 2000);
  const pages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;

  return (
    <div className="space-y-5">
      <PageHeader title="Data browser" subtitle="Read-only. Twelve whitelisted tables and their whitelisted columns: never users, refresh tokens or any hash.">
        <LiveBadge mode={mode} />
      </PageHeader>
      <Card title="Table">
        <div className="segmented max-w-full flex-wrap" role="group" aria-label="Table">
          {(data?.tables ?? [initial]).map((t) => (
            <button key={t} onClick={() => { setTable(t); setPage(0); }} className="neo-chip" aria-pressed={t === table}>{t}</button>
          ))}
        </div>
      </Card>
      <Card title={`${table} · ${data?.total ?? "…"} rows`} right={
        <div className="flex items-center gap-2 text-xs font-semibold">
          <Btn disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>Prev</Btn>
          <span>page {page + 1} of {pages}</span>
          <Btn disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>Next</Btn>
        </div>}>
        {last && !last.ok && <p className="font-semibold">{last.message}</p>}
        {data && (
          <Table head={data.columns} empty={data.rows.length === 0 ? "No rows." : undefined}>
            {data.rows.map((r, i) => (
              <tr key={i}>{data.columns.map((c) => (<td key={c} className="max-w-72 truncate px-3 py-1.5 font-mono text-xs" title={cell(r[c])}>{cell(r[c])}</td>))}</tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}
