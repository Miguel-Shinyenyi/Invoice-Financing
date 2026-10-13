import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { connect, type Harness } from "../helpers/harness.js";
import {
  healthyLab, IDS, LEDGER_MISMATCH_ROW, MISMATCH_ROW, roleOf, SANDBOX_PASSWORD, type FakeLab,
} from "../helpers/fakeLab.js";

let lab: FakeLab;
let h: Harness;

async function start(env: Record<string, string | undefined> = {}) {
  lab = healthyLab();
  await lab.start();
  h = await connect(lab.baseUrl, { env });
}

afterEach(async () => {
  await h?.close();
  await lab?.stop();
});

describe("tool list", () => {
  beforeEach(() => start());

  it("registers the ten read tools and no write tool by default", async () => {
    const names = (await h.client.listTools()).tools.map((t) => t.name).sort();
    expect(names).toEqual(
      [
        "explain_state_machine", "get_account", "get_invoice", "get_reconciliation_summary", "get_settlement",
        "get_system_status", "list_account_settlements", "list_ledger_mismatches", "list_open_reconciliation_mismatches", "list_settlements",
      ].sort(),
    );
    expect(h.built.tools.every((t) => t.kind === "read")).toBe(true);
  });

  it("describes every tool for an agent: what it returns and what to call next", async () => {
    for (const t of (await h.client.listTools()).tools) {
      expect(t.description ?? "", t.name).toMatch(/Returns|Lists/);
      expect(t.description ?? "", t.name).toMatch(/Next:|Start here|Use it|reads only/i);
      expect(t.annotations, t.name).toBeDefined();
    }
  });

  it("marks get_account as not read-only, because the ledger check can record a mismatch", async () => {
    const tools = (await h.client.listTools()).tools;
    const account = tools.find((t) => t.name === "get_account");
    expect(account?.annotations?.readOnlyHint).toBe(false);
    expect(account?.description).toMatch(/NOT purely read-only/);
    expect(account?.description).toMatch(/once per account/);
    for (const t of tools.filter((t) => t.name !== "get_account")) expect(t.annotations?.readOnlyHint, t.name).toBe(true);
  });
});

describe("get_system_status", () => {
  beforeEach(() => start());

  it("reports health, timers and the persona accounts, and drops the sandbox passwords", async () => {
    const r = await h.call("get_system_status");
    expect(r.isError).toBeFalsy();
    const d = h.data(r) as { services: { status: string }[]; timeCompression: { reconciliationIntervalMs: number }; personas: Record<string, unknown>[] };
    expect(d.services).toHaveLength(7);
    expect(d.timeCompression.reconciliationIntervalMs).toBe(5000);
    expect(d.personas.find((p) => p.role === "READ_ONLY")?.ownedAccounts).toEqual([IDS.accountOwned]);
    expect(JSON.stringify(r)).not.toContain(SANDBOX_PASSWORD);
    expect(JSON.stringify(r)).not.toContain("sandboxPassword");
    expect(h.text(r)).toMatch(/^Lab sandbox is healthy: all 7 services up/);
  });

  it("says which service is down", async () => {
    lab.on("GET /lab/health", { body: { checks: [{ name: "jaeger", status: "DOWN", latencyMs: 1, detail: "unreachable" }, { name: "kafka", status: "UP", latencyMs: 2 }] } });
    expect(h.text(await h.call("get_system_status"))).toMatch(/degraded: down: jaeger/);
  });
});

describe("get_settlement", () => {
  beforeEach(() => start());

  it("returns a confirmed settlement with its ledger entries and the transitions read from the engine", async () => {
    const r = await h.call("get_settlement", { settlementId: IDS.settlementConfirmed });
    const d = h.data(r) as any;
    expect(d.settlement.status).toBe("CONFIRMED");
    expect(d.settlement.externalRef).toBe("MOCK-SEED-18");
    expect(d.ledgerEntries).toHaveLength(2);
    expect(d.statusInfo.possibleNext).toEqual(["REVERSED"]);
    expect(d.stranded).toBe(false);
    expect(lab.count("GET /lab/state-machines")).toBe(1);
  });

  it("reads the transition rules from the engine, not from a copy", async () => {
    // Change the engine's rules and the answer changes with them.
    lab.on("GET /lab/state-machines", { body: { settlement: { states: [{ name: "CONFIRMED", terminal: true, noCodePath: false, note: null }], transitions: [] }, invoice: { states: [], transitions: [] }, advance: { states: [], transitions: [] } } });
    const d = h.data(await h.call("get_settlement", { settlementId: IDS.settlementConfirmed })) as any;
    expect(d.statusInfo.terminal).toBe(true);
    expect(d.statusInfo.possibleNext).toEqual([]);
    expect(d.statusInfo.meaning).toMatch(/terminal/);
  });

  it("flags a stranded UNKNOWN settlement and names Known gap 1", async () => {
    const r = await h.call("get_settlement", { settlementId: IDS.settlementStranded });
    const d = h.data(r) as any;
    expect(d.stranded).toBe(true);
    expect(d.settlement.externalRef).toBeNull();
    expect(d.strandedExplanation).toMatch(/no external reference/);
    expect(d.strandedExplanation).toMatch(/Known gap 1/);
    expect(d.statusInfo.possibleNext).toEqual(["CONFIRMED", "FAILED"]);
    expect(h.text(r)).toMatch(/stranded/);
  });

  it("rejects a malformed id before any request is made", async () => {
    const before = lab.requests.length;
    const r = await h.call("get_settlement", { settlementId: "not-a-uuid" });
    expect(r.isError).toBe(true);
    expect(lab.requests.length).toBe(before);
  });

  it("maps an unknown id to a short not_found", async () => {
    lab.on("GET /lab/settlements/00000000-0000-0000-0000-00000000dead/inspect", { status: 404, body: { status: 404, message: "No settlement 00000000-0000-0000-0000-00000000dead" } });
    const r = await h.call("get_settlement", { settlementId: "00000000-0000-0000-0000-00000000dead" });
    expect(r.isError).toBe(true);
    expect(h.data(r).error).toBe("not_found");
  });
});

describe("list tools: truncation is always reported", () => {
  beforeEach(() => start());

  const rows = (n: number) => Array.from({ length: n }, (_, i) => ({ ...MISMATCH_ROW, id: `71000000-0000-0000-0000-${String(i).padStart(12, "0")}` }));

  it("cuts to 20 by default and says 'showing 20 of 134'", async () => {
    lab.on("GET /lab/reconciliation/mismatches", { body: rows(134) });
    const r = await h.call("list_open_reconciliation_mismatches");
    const d = h.data(r) as any;
    expect(d.items).toHaveLength(20);
    expect(d.total).toBe(134);
    expect(d.notice).toBe("showing 20 of 134");
    expect(h.text(r)).toContain("showing 20 of 134");
  });

  it("honours a limit of 100 and refuses 101", async () => {
    await h.close();
    await lab.stop();
    await start({ MCP_MAX_RESPONSE_CHARS: "200000" });
    lab.on("GET /lab/reconciliation/mismatches", { body: rows(134) });
    expect((h.data(await h.call("list_open_reconciliation_mismatches", { limit: 100 })) as any).items).toHaveLength(100);
    expect((await h.call("list_open_reconciliation_mismatches", { limit: 101 })).isError).toBe(true);
    expect((await h.call("list_open_reconciliation_mismatches", { limit: 0 })).isError).toBe(true);
  });

  it("says nothing about truncation when everything is shown", async () => {
    const d = h.data(await h.call("list_open_reconciliation_mismatches")) as any;
    expect(d.notice).toBeNull();
    expect(d.items).toHaveLength(1);
  });

  it("cuts to the size budget, and reports it, even under the row limit", async () => {
    await h.close();
    await lab.stop();
    await start({ MCP_MAX_RESPONSE_CHARS: "2000" });
    lab.on("GET /lab/reconciliation/mismatches", { body: rows(100) });
    const r = await h.call("list_open_reconciliation_mismatches", { limit: 100 });
    const d = h.data(r) as any;
    expect(d.items.length).toBeLessThan(100);
    expect(d.notice).toBe(`showing ${d.items.length} of 100`);
    expect(h.text(r).length).toBeLessThanOrEqual(2000);
  });

  it("passes the page size and status through to a paged engine list, and reports the page totals", async () => {
    lab.on("GET /settlements", (req) => ({
      body: { content: Array.from({ length: Number(req.query.get("size")) }, (_, i) => ({ settlementId: `s${i}`, status: req.query.get("status") })), page: { size: 5, number: 1, totalElements: 134, totalPages: 27 } },
    }));
    const r = await h.call("list_settlements", { status: "UNKNOWN", limit: 5, page: 1 });
    const d = h.data(r) as any;
    const req = lab.requests.find((q) => q.path === "/settlements")!;
    expect(req.query.get("status")).toBe("UNKNOWN");
    expect(req.query.get("size")).toBe("5");
    expect(req.query.get("page")).toBe("1");
    expect(roleOf(req.headers.authorization)).toBe("READ_ONLY");
    expect(d.totalMatching).toBe(134);
    expect(d.page).toBe(1);
    expect(d.items).toHaveLength(5);
    expect(d.notice).toBe("showing 5 of 134");
    expect(d.nextPage).toBe(2);
  });

  it("refuses a lower-case or odd status filter", async () => {
    expect((await h.call("list_settlements", { status: "unknown; drop table" })).isError).toBe(true);
  });
});

describe("get_account", () => {
  beforeEach(() => start());

  it("returns the balance and a passed ledger check for an owned account", async () => {
    lab.on(`GET /accounts/${IDS.accountOwned}`, (req) => ({
      body: roleOf(req.headers.authorization) === "READ_ONLY" ? { id: IDS.accountOwned, ownerId: "o", balance: 4950.0, currency: "USD", createdAt: "x" } : { status: 401 },
    }));
    const d = h.data(await h.call("get_account", { accountId: IDS.accountOwned })) as any;
    expect(d.balance).toBe(4950);
    expect(d.ledgerCheck).toBe("CONSISTENT");
  });

  it("reports a drifted account as a MISMATCH result with stored, computed and the difference", async () => {
    lab.on(`GET /accounts/${IDS.accountDrifted}`, {
      status: 500,
      body: { status: 500, message: `Ledger account ${IDS.accountDrifted} is inconsistent: stored balance 1525.0000 does not match net of ledger entries 1500.0000` },
    });
    const r = await h.call("get_account", { accountId: IDS.accountDrifted });
    expect(r.isError).toBeFalsy();
    const d = h.data(r) as any;
    expect(d.ledgerCheck).toBe("MISMATCH");
    expect(d.storedBalance).toBe(1525);
    expect(d.computedBalance).toBe(1500);
    expect(d.difference).toBe(25);
    expect(d.mismatchId).toBe(LEDGER_MISMATCH_ROW.id);
    expect(d.sideEffectNote).toMatch(/recorded/);
  });

  it("maps 403 to access_denied with the reason", async () => {
    lab.on(`GET /accounts/${IDS.accountOwned}`, { status: 403, body: { status: 403, message: "Insufficient permissions for this resource" } });
    const r = await h.call("get_account", { accountId: IDS.accountOwned });
    expect(r.isError).toBe(true);
    expect(h.data(r).error).toBe("access_denied");
    expect(h.text(r)).toMatch(/READ_ONLY persona sees only its own accounts/);
  });

  it("does not hide an unrelated 500 as a ledger mismatch", async () => {
    lab.on(`GET /accounts/${IDS.accountOwned}`, { status: 500, body: { status: 500, message: "boom" } });
    const r = await h.call("get_account", { accountId: IDS.accountOwned });
    expect(r.isError).toBe(true);
    expect(h.data(r).error).toBe("upstream_error");
  });
});

describe("reconciliation tools", () => {
  beforeEach(() => start());

  it("derives the mismatch kind from the two states", async () => {
    lab.on("GET /lab/reconciliation/mismatches", {
      body: [
        MISMATCH_ROW,
        { ...MISMATCH_ROW, id: "m2", internalState: "CONFIRMED", externalState: "FAILED" },
        { ...MISMATCH_ROW, id: "m3", internalState: "CONFIRMED", externalState: null },
      ],
    });
    const items = (h.data(await h.call("list_open_reconciliation_mismatches")) as any).items;
    expect(items.map((i: any) => i.kind)).toEqual(["AMOUNT_OR_CURRENCY_DIFFERS", "STATUS_DIFFERS", "MISSING_EXTERNAL_RECORD"]);
    expect(items[0].settlementId).toBe(IDS.mismatchSettlement);
  });

  it("lists ledger mismatches with stored minus computed", async () => {
    const d = h.data(await h.call("list_ledger_mismatches")) as any;
    expect(d.items[0]).toMatchObject({ accountId: IDS.accountDrifted, storedBalance: 1525, computedBalance: 1500, difference: 25 });
    expect(lab.requests.at(-1)?.query.get("status")).toBe("OPEN");
  });

  it("summarises runs and stranded settlements and explains why they are stranded", async () => {
    const r = await h.call("get_reconciliation_summary");
    const d = h.data(r) as any;
    expect(d.openSettlementMismatches).toBe(1);
    expect(d.openLedgerMismatches).toBe(1);
    expect(d.strandedUnknownCount).toBe(1);
    expect(d.strandedUnknown[0].externalRef).toBeNull();
    expect(d.strandedExplanation).toMatch(/no external reference/);
    expect((await h.client.listTools()).tools.find((t) => t.name === "get_reconciliation_summary")?.description).toMatch(/no external reference.*Known gap 1/s);
  });
});

describe("get_invoice and explain_state_machine", () => {
  beforeEach(() => start());

  it("returns the invoice with its advance", async () => {
    lab.on(`GET /invoices/${IDS.invoice}`, { body: { id: IDS.invoice, status: "FINANCED", amount: 1000, advance: { status: "DISBURSED", repaidSettlementId: null } } });
    const r = await h.call("get_invoice", { invoiceId: IDS.invoice });
    expect(h.text(r)).toMatch(/FINANCED, advance DISBURSED/);
  });

  it("explains the state machines from the engine, including states with no code path", async () => {
    const d = h.data(await h.call("explain_state_machine", { machine: "settlement" })) as any;
    expect(Object.keys(d.machines)).toEqual(["settlement"]);
    expect(d.machines.settlement.transitions).toContain("UNKNOWN -> CONFIRMED");
    expect(d.machines.settlement.states.find((s: any) => s.name === "REVERSED").noCodePath).toBe(true);
    expect(Object.keys((h.data(await h.call("explain_state_machine")) as any).machines)).toEqual(["settlement", "invoice", "advance"]);
  });
});
