import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

// Expected values for the integration tests and the evals come from the seed files themselves:
// lab/seed/lab-seed.sql and lab/seed/scenarios.json. Nothing here is typed in by hand.
const SEED_DIR = resolve(import.meta.dirname, "..", "..", "lab", "seed");
const sql = readFileSync(resolve(SEED_DIR, "lab-seed.sql"), "utf8");

/** Postgres md5('text')::uuid, which the seed uses for settlement ids. */
export function md5Uuid(text: string): string {
  const h = createHash("md5").update(text).digest("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export const settlementId = (n: number) => md5Uuid(`seed-settlement-${n}`);

export interface SeedAccount {
  id: string;
  ownerId: string;
  opening: number;
}
export interface SeedSettlement {
  n: number;
  id: string;
  src: string;
  dst: string;
  amount: number;
  status: string;
  hasRef: boolean;
}

function block(startMarker: RegExp): string {
  const m = startMarker.exec(sql);
  if (!m) throw new Error(`seed marker not found: ${startMarker}`);
  const rest = sql.slice(m.index + m[0].length);
  return rest.slice(0, rest.indexOf(";"));
}

export const accounts: SeedAccount[] = [
  ...block(/insert into seed_accounts \(id, owner_id, opening\) values/).matchAll(/\('([0-9a-f-]{36})',\s*'([0-9a-f-]{36})',\s*([\d.]+)\)/g),
].map((m) => ({ id: m[1]!, ownerId: m[2]!, opening: Number(m[3]) }));

export const settlements: SeedSettlement[] = [
  ...block(/insert into seed_settlements values/).matchAll(
    /\((\d+),\s*'([0-9a-f-]{36})',\s*'([0-9a-f-]{36})',\s*([\d.]+),\s*'([A-Z]+)',\s*(true|false),\s*\d+\)/g,
  ),
].map((m) => ({ n: Number(m[1]), id: settlementId(Number(m[1])), src: m[2]!, dst: m[3]!, amount: Number(m[4]), status: m[5]!, hasRef: m[6] === "true" }));

const drift = /update ledger_accounts set balance = balance \+ ([\d.]+) where id = '([0-9a-f-]{36})'/.exec(sql);
if (!drift) throw new Error("seed drift statement not found");
export const DRIFT_AMOUNT = Number(drift[1]);
export const DRIFTED_ACCOUNT = drift[2]!;

/** Balance implied by the ledger entries: the opening entry, plus credits, minus debits of CONFIRMED and REVERSED settlements. */
export function netOfEntries(accountId: string): number {
  const a = accounts.find((x) => x.id === accountId);
  if (!a) throw new Error(`no seed account ${accountId}`);
  let net = a.opening;
  for (const s of settlements.filter((x) => x.status === "CONFIRMED" || x.status === "REVERSED")) {
    if (s.dst === accountId) net += s.amount;
    if (s.src === accountId) net -= s.amount;
  }
  return Math.round(net * 10_000) / 10_000;
}

export function ownedBy(ownerId: string): string[] {
  return accounts.filter((a) => a.ownerId === ownerId).map((a) => a.id);
}

/** The persona that owns accounts 4000...: read from the users insert for lab-readonly. */
export const READ_ONLY_OWNER = /'lab-readonly',\s*'[^']*',\s*'READ_ONLY',\s*'([0-9a-f-]{36})'/.exec(sql)![1]!;

const openMismatchRow = /\('(71[0-9a-f-]{34})',\s*'[0-9a-f-]{36}',\s*md5\('seed-settlement-(\d+)'\)::uuid,\s*'([A-Z]+)',\s*(null|'[A-Z]+'),\s*'([^']*)',\s*'OPEN'/.exec(sql);
if (!openMismatchRow) throw new Error("seed open mismatch not found");
export const OPEN_MISMATCH = {
  id: openMismatchRow[1]!,
  settlementId: settlementId(Number(openMismatchRow[2])),
  internalState: openMismatchRow[3]!,
  externalState: openMismatchRow[4] === "null" ? null : openMismatchRow[4]!.replace(/'/g, ""),
  details: openMismatchRow[5]!,
};

/** The duplicate-key pair accounts: scenarios.json names the burst, the seed declares the balances 1000 and 0. */
const scenarios = JSON.parse(readFileSync(resolve(SEED_DIR, "scenarios.json"), "utf8")) as { id: string }[];
export const HAS_DUPLICATE_BURST_SCENARIO = scenarios.some((s) => s.id === "load-duplicate");
export const DUP_PAIR = accounts.filter((a) => a.id.startsWith("20000000-")).map((a) => a.id);

export const stranded = settlements.filter((s) => s.status === "UNKNOWN" && !s.hasRef);
export const confirmedWithEntries = settlements.filter((s) => s.status === "CONFIRMED");
