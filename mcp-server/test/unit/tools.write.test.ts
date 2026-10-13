import { afterEach, describe, expect, it } from "vitest";
import { connect, type Harness } from "../helpers/harness.js";
import { healthyLab, IDS, roleOf, type FakeLab, type Recorded } from "../helpers/fakeLab.js";

let lab: FakeLab;
let h: Harness;

async function start(env: Record<string, string | undefined>) {
  lab = healthyLab();
  await lab.start();
  h = await connect(lab.baseUrl, { env });
}

afterEach(async () => {
  await h?.close();
  await lab?.stop();
});

const SRC = "20000000-0000-0000-0000-000000000001";
const DST = "20000000-0000-0000-0000-000000000002";
const args = { sourceAccountId: SRC, destinationAccountId: DST, amount: "12.50", currency: "USD", idempotencyKey: IDS.key };

/** A settlement endpoint that behaves like the engine's: one settlement per key, 409 on a changed body. */
function idempotentSettlements(l: FakeLab) {
  const byKey = new Map<string, { body: string; response: unknown }>();
  let created = 0;
  l.on("POST /settlements", (req: Recorded) => {
    const key = String(req.headers["idempotency-key"] ?? "");
    const body = JSON.stringify(req.body);
    const seen = byKey.get(key);
    if (seen) {
      return seen.body === body ? { status: 201, body: seen.response } : { status: 409, body: { status: 409, message: "Idempotency key reused with a different request body" } };
    }
    created += 1;
    const response = { settlementId: `5e771e00-0000-4000-8000-${String(created).padStart(12, "0")}`, ...(req.body as object), status: "CONFIRMED", externalRef: "MOCK-1", createdAt: "x", updatedAt: "x" };
    byKey.set(key, { body, response });
    return { status: 201, body: response };
  });
  return () => created;
}

describe("write tools are absent unless MCP_ALLOW_WRITES=true", () => {
  it("lists no write tool by default", async () => {
    await start({});
    const names = (await h.client.listTools()).tools.map((t) => t.name);
    for (const w of ["create_settlement", "run_reconciliation", "resolve_settlement_mismatch"]) expect(names).not.toContain(w);
  });

  it("cannot call a write tool by name when writes are off, and sends no request", async () => {
    await start({});
    const before = lab.requests.length;
    const r = await h.client.callTool({ name: "create_settlement", arguments: args }).catch((e: Error) => ({ isError: true, content: [{ type: "text", text: e.message }] }));
    expect((r as { isError?: boolean }).isError).toBe(true);
    expect(lab.requests.length).toBe(before);
  });

  it.each(["1", "yes", "TRUE", ""])("stays off for MCP_ALLOW_WRITES=%j", async (v) => {
    await start({ MCP_ALLOW_WRITES: v });
    expect((await h.client.listTools()).tools.map((t) => t.name)).not.toContain("create_settlement");
  });

  it("registers the three write tools when the flag is exactly true", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    const tools = (await h.client.listTools()).tools;
    expect(tools.map((t) => t.name)).toEqual(expect.arrayContaining(["create_settlement", "run_reconciliation", "resolve_settlement_mismatch"]));
    expect(h.built.tools.filter((t) => t.kind === "write").map((t) => t.name).sort()).toEqual(["create_settlement", "resolve_settlement_mismatch", "run_reconciliation"]);
    for (const name of ["create_settlement", "run_reconciliation", "resolve_settlement_mismatch"]) {
      expect(tools.find((t) => t.name === name)?.annotations?.readOnlyHint).toBe(false);
    }
  });
});

describe("create_settlement", () => {
  it("teaches idempotency in its description", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    const d = (await h.client.listTools()).tools.find((t) => t.name === "create_settlement")!.description!;
    expect(d).toMatch(/MUST supply idempotencyKey/);
    expect(d).toMatch(/never makes one for you/);
    expect(d).toMatch(/SAME key/);
    expect(d).toMatch(/identical result/);
    expect(d).toMatch(/conflict/);
  });

  it("requires an idempotency key and never generates one", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    idempotentSettlements(lab);
    const { idempotencyKey: _k, ...withoutKey } = args;
    const before = lab.count("POST /settlements");
    const r = await h.call("create_settlement", withoutKey);
    expect(r.isError).toBe(true);
    expect(lab.count("POST /settlements")).toBe(before);
    expect((await h.call("create_settlement", { ...args, idempotencyKey: "not-a-uuid" })).isError).toBe(true);
    expect((await h.call("create_settlement", { ...args, idempotencyKey: "" })).isError).toBe(true);
  });

  it("validates amount and currency before sending", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    idempotentSettlements(lab);
    for (const bad of [{ amount: "0" }, { amount: "-5" }, { amount: "1e3" }, { amount: "12.12345" }, { amount: "abc" }, { currency: "usd" }, { currency: "US" }]) {
      expect((await h.call("create_settlement", { ...args, ...bad })).isError, JSON.stringify(bad)).toBe(true);
    }
    expect(lab.count("POST /settlements")).toBe(0);
  });

  it("sends the ADMIN token and the caller's key, and returns the engine's result", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    idempotentSettlements(lab);
    const r = await h.call("create_settlement", args);
    expect(r.isError).toBeFalsy();
    const req = lab.requests.find((q) => q.path === "/settlements")!;
    expect(req.headers["idempotency-key"]).toBe(IDS.key);
    expect(roleOf(req.headers.authorization)).toBe("ADMIN");
    expect(req.body).toEqual({ sourceAccountId: SRC, destinationAccountId: DST, amount: 12.5, currency: "USD" });
    expect((h.data(r) as any).status).toBe("CONFIRMED");
  });

  it("returns the same settlement for the same key and creates one", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    const created = idempotentSettlements(lab);
    const a = h.data(await h.call("create_settlement", args)) as any;
    const b = h.data(await h.call("create_settlement", args)) as any;
    expect(b.settlementId).toBe(a.settlementId);
    expect(created()).toBe(1);
  });

  it("reports a conflict when the key is reused with a different body", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    idempotentSettlements(lab);
    await h.call("create_settlement", args);
    const r = await h.call("create_settlement", { ...args, amount: "99" });
    expect(r.isError).toBe(true);
    expect(h.data(r).error).toBe("conflict");
    expect(h.text(r)).toMatch(/different request body/);
  });

  it("maps 422 and 404 to short messages", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    lab.on("POST /settlements", { status: 422, body: { status: 422, message: "Insufficient balance on account" } });
    expect(h.data(await h.call("create_settlement", args)).error).toBe("unprocessable");
    lab.on("POST /settlements", { status: 404, body: { status: 404, message: "Account not found" } });
    expect(h.data(await h.call("create_settlement", args)).error).toBe("not_found");
  });
});

describe("run_reconciliation and resolve_settlement_mismatch", () => {
  it("runs a reconciliation as ADMIN", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    lab.on("POST /reconciliation/runs", { body: { id: "r9", status: "COMPLETED", recordsChecked: 13, mismatchesFound: 1, startedAt: "x", finishedAt: "y" } });
    const r = await h.call("run_reconciliation");
    expect((h.data(r) as any).mismatchesFound).toBe(1);
    expect(roleOf(lab.requests.find((q) => q.path === "/reconciliation/runs")!.headers.authorization)).toBe("ADMIN");
  });

  it("requires a reason, and says resolving changes no data", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    lab.on(`POST /reconciliation/mismatches/${IDS.mismatch}/resolve`, (req) => ({ body: { id: IDS.mismatch, settlementId: IDS.mismatchSettlement, resolutionStatus: "RESOLVED", resolvedAt: "x", reason: (req.body as any).reason } }));
    expect((await h.call("resolve_settlement_mismatch", { mismatchId: IDS.mismatch })).isError).toBe(true);
    expect((await h.call("resolve_settlement_mismatch", { mismatchId: IDS.mismatch, reason: "  " })).isError).toBe(true);
    expect(lab.count(`POST /reconciliation/mismatches/${IDS.mismatch}/resolve`)).toBe(0);

    const r = await h.call("resolve_settlement_mismatch", { mismatchId: IDS.mismatch, reason: "External record is stale" });
    const d = h.data(r) as any;
    expect(d.resolutionStatus).toBe("RESOLVED");
    expect(d.settlementChanged).toBe(false);
    expect(d.ledgerChanged).toBe(false);
    expect(d.note).toMatch(/Known gap 2/);
    expect((lab.requests.find((q) => q.path.endsWith("/resolve"))!.body as any).reason).toBe("External record is stale");
    const desc = (await h.client.listTools()).tools.find((t) => t.name === "resolve_settlement_mismatch")!.description!;
    expect(desc).toMatch(/does NOT change the settlement/);
    expect(desc).toMatch(/Known gap 2/);
  });

  it("maps an already-resolved mismatch to a conflict", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    lab.on(`POST /reconciliation/mismatches/${IDS.mismatch}/resolve`, { status: 409, body: { status: 409, message: "Mismatch already resolved" } });
    expect(h.data(await h.call("resolve_settlement_mismatch", { mismatchId: IDS.mismatch, reason: "again" })).error).toBe("conflict");
  });
});
