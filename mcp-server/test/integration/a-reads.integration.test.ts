import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  DRIFT_AMOUNT, DRIFTED_ACCOUNT, OPEN_MISMATCH, READ_ONLY_OWNER, accounts, netOfEntries, ownedBy, settlementId, settlements, stranded,
} from "../../support/seed.js";
import { labReachable, liveClient, resetLab, SKIP_NOTE } from "../helpers/live.js";

const up = await labReachable();
if (!up) process.stderr.write(`\n[integration] ${SKIP_NOTE}\n`);

describe.skipIf(!up)("live Lab: read tools against the seed", () => {
  let c: Awaited<ReturnType<typeof liveClient>>;
  const owned = ownedBy(READ_ONLY_OWNER);

  beforeAll(async () => {
    await resetLab(); // back to the seed. A refusal because of the cooldown is fine: see below.
    c = await liveClient(false);
    // If an earlier run resolved the seeded mismatch and the reset was refused, the engine opens a
    // fresh one on its next run (resolving changes no data). Wait for it rather than assume.
    for (let i = 0; i < 20; i += 1) {
      const r = await c.call("list_open_reconciliation_mismatches");
      if (r.data.items?.some((m: any) => m.settlementId === OPEN_MISMATCH.settlementId)) break;
      await new Promise((resolve) => setTimeout(resolve, 1_000));
    }
  });
  afterAll(async () => c?.close());

  it("lists exactly ten read tools and no write tool", async () => {
    const names = (await c.client.listTools()).tools.map((t) => t.name);
    expect(names).toHaveLength(10);
    expect(names).not.toContain("create_settlement");
  });

  it("get_system_status: a sandbox, with the compressed timers and the READ_ONLY persona's accounts", async () => {
    const r = await c.call("get_system_status");
    expect(r.isError).toBe(false);
    expect(r.data.sandbox).toBe(true);
    expect(r.data.timeCompression.reconciliationIntervalMs).toBeLessThan(60_000);
    const status = Object.fromEntries(r.data.services.map((s: any) => [s.name, s.status]));
    expect(status.backend).toBe("UP");
    expect(status.postgres).toBe("UP");
    const ro = r.data.personas.find((p: any) => p.role === "READ_ONLY");
    expect([...ro.ownedAccounts].sort()).toEqual([...owned].sort());
    expect(JSON.stringify(r.raw)).not.toMatch(/lab-(admin|support|readonly)-sandbox|sandboxPassword/);
  });

  it("list_open_reconciliation_mismatches: the seeded open mismatch, with its kind", async () => {
    const r = await c.call("list_open_reconciliation_mismatches");
    const hit = r.data.items.find((m: any) => m.settlementId === OPEN_MISMATCH.settlementId);
    expect(hit, "the seeded open mismatch").toBeDefined();
    expect(hit.internalState).toBe(OPEN_MISMATCH.internalState);
    expect(hit.externalState).toBe(OPEN_MISMATCH.externalState);
    expect(hit.kind).toBe("AMOUNT_OR_CURRENCY_DIFFERS");
    // A mismatch the engine reopened prints the external amount as 99.00, the seed as 99.0000.
    const plain = (t: string) => t.replace(/(\d+)\.0+\b/g, "$1");
    expect(plain(hit.details)).toBe(plain(OPEN_MISMATCH.details));
  });

  it("list_ledger_mismatches: the drifted account, stored and computed from the seed", async () => {
    const r = await c.call("list_ledger_mismatches");
    const hit = r.data.items.find((m: any) => m.accountId === DRIFTED_ACCOUNT);
    expect(hit, "drifted account 50000000-...-0002").toBeDefined();
    expect(hit.computedBalance).toBe(netOfEntries(DRIFTED_ACCOUNT));
    expect(hit.storedBalance).toBe(netOfEntries(DRIFTED_ACCOUNT) + DRIFT_AMOUNT);
    expect(hit.difference).toBe(DRIFT_AMOUNT);
    expect(DRIFT_AMOUNT).toBe(25);
  });

  it("get_account on the drifted account: MISMATCH of +25.00 (and it says reading recorded a mismatch)", async () => {
    const r = await c.call("get_account", { accountId: DRIFTED_ACCOUNT });
    expect(r.isError).toBe(false);
    expect(r.data.ledgerCheck).toBe("MISMATCH");
    expect(r.data.difference).toBe(DRIFT_AMOUNT);
    expect(r.data.storedBalance).toBe(netOfEntries(DRIFTED_ACCOUNT) + DRIFT_AMOUNT);
    expect(r.data.sideEffectNote).toMatch(/recorded/);
  });

  it("get_account on an owned account: the balance the seed's entries imply, ledger check passed", async () => {
    const r = await c.call("get_account", { accountId: owned[0] });
    expect(r.data.ledgerCheck).toBe("CONSISTENT");
    expect(r.data.balance).toBe(netOfEntries(owned[0]!));
  });

  it("get_account as SUPPORT reads an account the READ_ONLY persona does not own", async () => {
    const other = accounts.find((a) => a.id.startsWith("10000000-"))!.id;
    const r = await c.call("get_account", { accountId: other });
    expect(r.isError).toBe(false);
    expect(r.data.ledgerCheck).toBe("CONSISTENT");
    expect(r.data.balance).toBe(netOfEntries(other));
  });

  it("get_settlement on a stranded UNKNOWN settlement: stranded, no external reference, Known gap 1", async () => {
    const s = stranded[0]!;
    const r = await c.call("get_settlement", { settlementId: s.id });
    expect(r.data.settlement.status).toBe("UNKNOWN");
    expect(r.data.settlement.externalRef).toBeNull();
    expect(r.data.stranded).toBe(true);
    expect(r.data.strandedExplanation).toMatch(/Known gap 1/);
    expect(r.data.ledgerEntries).toEqual([]);
    expect(r.data.statusInfo.possibleNext.sort()).toEqual(["CONFIRMED", "FAILED"]);
  });

  it("get_settlement on a confirmed settlement: its debit and credit entries from the seed", async () => {
    const s = settlements.find((x) => x.n === 1)!;
    const r = await c.call("get_settlement", { settlementId: s.id });
    expect(r.data.settlement.status).toBe("CONFIRMED");
    expect(r.data.settlement.amount).toBe(s.amount);
    const entries = r.data.ledgerEntries as { accountId: string; entryType: string; amount: number }[];
    expect(entries).toHaveLength(2);
    expect(entries.find((e) => e.entryType === "DEBIT")).toMatchObject({ accountId: s.src, amount: s.amount });
    expect(entries.find((e) => e.entryType === "CREDIT")).toMatchObject({ accountId: s.dst, amount: s.amount });
  });

  it("get_settlement on the settlement with the open mismatch: shows the mismatch", async () => {
    const r = await c.call("get_settlement", { settlementId: OPEN_MISMATCH.settlementId });
    expect(r.data.reconciliationMismatches.length).toBeGreaterThanOrEqual(1);
  });

  it("get_reconciliation_summary: the stranded UNKNOWN settlements are the seed's two, plus any swept from PENDING", async () => {
    const r = await c.call("get_reconciliation_summary");
    const ids = (r.data.strandedUnknown as { settlementId: string }[]).map((s) => s.settlementId);
    for (const s of stranded) expect(ids, `seed settlement ${s.n}`).toContain(s.id);
    expect(r.data.strandedUnknown.every((s: any) => s.externalRef === null)).toBe(true);
    expect(r.data.openLedgerMismatches).toBeGreaterThanOrEqual(1);
    expect(r.data.strandedExplanation).toMatch(/no external reference/i);
  });

  it("list_settlements as SUPPORT: every seeded settlement, not only the READ_ONLY persona's", async () => {
    const r = await c.call("list_settlements", { limit: 100 });
    const items = r.data.items as { settlementId: string; sourceAccountId: string; destinationAccountId: string }[];
    const ids = items.map((i) => i.settlementId);
    for (const s of settlements) expect(ids, `seed settlement ${s.id}`).toContain(s.id);
    expect(items.some((i) => !owned.includes(i.sourceAccountId) && !owned.includes(i.destinationAccountId))).toBe(true);
  });

  it("list_settlements with a status filter, and list_account_settlements for an owned account", async () => {
    const confirmed = await c.call("list_settlements", { status: "CONFIRMED" });
    expect(confirmed.data.items.every((i: any) => i.status === "CONFIRMED")).toBe(true);
    const mine = await c.call("list_account_settlements", { accountId: owned[0] });
    const want = settlements.filter((s) => s.src === owned[0] || s.dst === owned[0]).length;
    expect(mine.data.total).toBeGreaterThanOrEqual(want);
    expect(mine.data.items.map((i: any) => i.settlementId)).toContain(settlementId(7));
  });

  it("get_invoice as SUPPORT returns a seeded invoice", async () => {
    const r = await c.call("get_invoice", { invoiceId: "80000000-0000-0000-0000-000000000002" });
    expect(r.isError).toBe(false);
    expect(r.data.invoice.id).toBe("80000000-0000-0000-0000-000000000002");
  });

  it("explain_state_machine: read from the engine, with the states that have no code path", async () => {
    const r = await c.call("explain_state_machine");
    const settlement = r.data.machines.settlement;
    expect(settlement.transitions).toContain("UNKNOWN -> CONFIRMED");
    expect(settlement.states.find((s: any) => s.name === "REVERSED").noCodePath).toBe(true);
    expect(r.data.machines.advance.states.find((s: any) => s.name === "DEFAULTED").noCodePath).toBe(true);
  });

  it("serves the OpenAPI document and the docs as resources", async () => {
    const api = await c.client.readResource({ uri: "settlement-engine://openapi" });
    expect(JSON.parse((api.contents[0] as { text: string }).text).paths["/settlements"]).toBeDefined();
    const doc = await c.client.readResource({ uri: "settlement-engine://docs/reconciliation" });
    expect((doc.contents[0] as { text: string }).text).toMatch(/Known gap|idempot/i);
  });
});

describe.skipIf(!up)("live Lab: MCP_READ_PERSONA=READ_ONLY keeps the narrow view", () => {
  let c: Awaited<ReturnType<typeof liveClient>>;
  const owned = ownedBy(READ_ONLY_OWNER);
  beforeAll(async () => {
    c = await liveClient(false, "READ_ONLY");
  });
  afterAll(async () => c?.close());

  it("get_account on someone else's consistent account: access_denied, not a leak", async () => {
    const other = accounts.find((a) => a.id.startsWith("10000000-"))!.id;
    const r = await c.call("get_account", { accountId: other });
    expect(r.isError).toBe(true);
    expect(r.data.error).toBe("access_denied");
  });

  it("list_settlements: only settlements that touch the READ_ONLY persona's accounts", async () => {
    const r = await c.call("list_settlements", { limit: 100 });
    const items = r.data.items as { sourceAccountId: string; destinationAccountId: string }[];
    expect(items.length).toBeGreaterThanOrEqual(2);
    for (const i of items) expect(owned.includes(i.sourceAccountId) || owned.includes(i.destinationAccountId)).toBe(true);
  });

  it("get_invoice: a seeded invoice is not the READ_ONLY persona's, so access_denied", async () => {
    const r = await c.call("get_invoice", { invoiceId: "80000000-0000-0000-0000-000000000002" });
    expect(r.isError).toBe(true);
    expect(r.data.error).toBe("access_denied");
  });
});
