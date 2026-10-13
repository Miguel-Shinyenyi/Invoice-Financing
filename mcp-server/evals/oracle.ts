import { browse } from "../support/labData.js";

// The expected answers for the evals. Every value is read from the sandbox database through the
// Lab's data browser (raw HTTP, no MCP server involved), or from lab/seed. None comes from a model.

const n4 = (v: unknown) => Math.round(Number(v) * 10_000) / 10_000;

export interface DriftedAccount {
  accountId: string;
  stored: number;
  net: number;
  difference: number;
}

/** Accounts whose stored balance differs from the net of their ledger entries. */
export async function driftedAccounts(): Promise<DriftedAccount[]> {
  const [accounts, entries] = await Promise.all([browse("ledger_accounts", 6), browse("ledger_entries", 40)]);
  const net = new Map<string, number>();
  for (const e of entries) {
    const sign = e.entry_type === "DEBIT" ? -1 : 1; // OPENING and CREDIT add, DEBIT subtracts
    net.set(e.account_id, (net.get(e.account_id) ?? 0) + sign * Number(e.amount));
  }
  return accounts
    .map((a) => ({ accountId: a.id as string, stored: n4(a.balance), net: n4(net.get(a.id) ?? 0) }))
    .filter((a) => a.stored !== a.net)
    .map((a) => ({ ...a, difference: n4(a.stored - a.net) }));
}

export interface OpenMismatch {
  id: string;
  settlementId: string;
  /** Words an answer should use to describe this kind of mismatch. */
  kindWords: RegExp;
}

export async function openMismatches(): Promise<OpenMismatch[]> {
  const rows = await browse("reconciliation_mismatches", 4);
  return rows
    .filter((r) => r.resolution_status === "OPEN")
    .map((r) => ({
      id: r.id as string,
      settlementId: r.settlement_id as string,
      kindWords:
        r.external_state === null || r.external_state === undefined
          ? /no external record|missing|not found|no record/i
          : r.internal_state === r.external_state
            ? /amount|currency/i
            : /status/i,
    }));
}

export async function settlementRow(id: string) {
  const rows = await browse("settlements", 4);
  return rows.find((r) => r.id === id);
}

export async function settlementsWithKey(key: string): Promise<Record<string, any>[]> {
  return (await browse("settlements", 4)).filter((r) => r.idempotency_key === key);
}

export async function settlementCount(): Promise<number> {
  const res = await fetch(new URL("/lab/data/settlements", process.env.LAB_BASE_URL ?? "http://127.0.0.1:8080"));
  return ((await res.json()) as { total: number }).total;
}

export async function accountBalance(id: string): Promise<number> {
  const rows = await browse("ledger_accounts", 6);
  const row = rows.find((r) => r.id === id);
  if (!row) throw new Error(`no account ${id}`);
  return n4(row.balance);
}

export async function mismatchStatus(id: string): Promise<string | undefined> {
  const rows = await browse("reconciliation_mismatches", 4);
  return rows.find((r) => r.id === id)?.resolution_status as string | undefined;
}
