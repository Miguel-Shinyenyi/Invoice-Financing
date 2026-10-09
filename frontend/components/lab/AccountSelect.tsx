"use client";

import { useEffect, useState } from "react";
import { lab } from "@/lib/lab/client";
import { accountLabel } from "@/lib/lab/seedLabels";

interface Row {
  id: string;
  balance: number;
}

/** Account choices come from the sandbox's ledger_accounts table (so balances are live); the names are seed labels. */
export function AccountSelect({ id, value, onChange }: { id: string; value: string; onChange: (v: string) => void }) {
  const [rows, setRows] = useState<Row[]>([]);
  useEffect(() => {
    let live = true;
    lab.get<{ rows: Row[] }>("data/ledger_accounts", { columns: "id,balance" }).then((r) => {
      if (live && r.ok && r.data) setRows(r.data.rows);
    });
    return () => {
      live = false;
    };
  }, []);
  const options = [...rows].sort((a, b) => accountLabel(a.id).localeCompare(accountLabel(b.id)));
  return (
    <select id={id} value={value} onChange={(e) => onChange(e.target.value)} className="neo-input w-full min-w-0">
      {options.map((r) => (
        <option key={r.id} value={r.id}>{accountLabel(r.id)} · {Number(r.balance).toLocaleString("en-US")}</option>
      ))}
      {options.length === 0 && <option value={value}>{value}</option>}
    </select>
  );
}
