import { afterEach, describe, expect, it } from "vitest";
import { loadConfig } from "../../src/config.js";
import { LabClient } from "../../src/http.js";
import { createLogger } from "../../src/log.js";
import { connect, type Harness } from "../helpers/harness.js";
import { healthyLab, IDS, roleOf, type FakeLab } from "../helpers/fakeLab.js";

let lab: FakeLab;
let h: Harness;

async function start(opts: Parameters<typeof connect>[1] = {}) {
  lab = healthyLab();
  await lab.start();
  lab.on(`GET /accounts/${IDS.accountOwned}`, (r) => ({ body: { id: IDS.accountOwned, balance: 1, currency: "USD", ownerId: "o", createdAt: "x", who: roleOf(r.headers.authorization) } }));
  h = await connect(lab.baseUrl, opts);
}

afterEach(async () => {
  await h?.close();
  await lab?.stop();
});

describe("persona tokens", () => {
  it("uses READ_ONLY for reads when MCP_READ_PERSONA=READ_ONLY", async () => {
    await start({ env: { MCP_READ_PERSONA: "READ_ONLY" } });
    await h.call("get_account", { accountId: IDS.accountOwned });
    expect(lab.count("POST /lab/personas/READ_ONLY/token")).toBe(1);
    expect(lab.count("POST /lab/personas/SUPPORT/token")).toBe(0);
  });

  it("rejects an unknown MCP_READ_PERSONA, and never accepts ADMIN for reads", () => {
    expect(() => loadConfig({ MCP_READ_PERSONA: "ADMIN" })).toThrow(/SUPPORT or READ_ONLY/);
    expect(() => loadConfig({ MCP_READ_PERSONA: "support" })).toThrow(/SUPPORT or READ_ONLY/);
    expect(loadConfig({}).readPersona).toBe("SUPPORT");
  });

  it("never sends a read-persona token on a write, or an ADMIN token on a read", async () => {
    await start();
    const client = new LabClient(loadConfig({ LAB_BASE_URL: lab.baseUrl }), createLogger(() => {}));
    await expect(client.post("/settlements", { role: "SUPPORT", body: {} })).rejects.toThrow(/refused to send/);
    await expect(client.post("/settlements", { role: "READ_ONLY", body: {} })).rejects.toThrow(/refused to send/);
    await expect(client.get("/accounts/x", { role: "ADMIN" })).rejects.toThrow(/refused to send/);
    expect(lab.count("POST /lab/personas/SUPPORT/token") + lab.count("POST /lab/personas/READ_ONLY/token") + lab.count("POST /lab/personas/ADMIN/token")).toBe(0);
    expect(lab.requests.filter((r) => r.path === "/settlements")).toHaveLength(0);
  });

  it("uses SUPPORT for reads by default, never ADMIN", async () => {
    await start();
    await h.call("get_account", { accountId: IDS.accountOwned });
    expect(lab.count("POST /lab/personas/SUPPORT/token")).toBe(1);
    expect(lab.count("POST /lab/personas/ADMIN/token")).toBe(0);
  });

  it("fetches a token lazily: tokenless Lab views need none", async () => {
    await start();
    await h.call("get_system_status");
    await h.call("list_open_reconciliation_mismatches");
    expect(lab.count("POST /lab/personas/SUPPORT/token") + lab.count("POST /lab/personas/READ_ONLY/token") + lab.count("POST /lab/personas/ADMIN/token")).toBe(0);
  });

  it("reuses a token while it is valid", async () => {
    await start();
    await h.call("get_account", { accountId: IDS.accountOwned });
    await h.call("get_account", { accountId: IDS.accountOwned });
    expect(lab.count("POST /lab/personas/SUPPORT/token")).toBe(1);
  });

  it("shares one token fetch between concurrent calls", async () => {
    await start();
    await Promise.all([1, 2, 3, 4].map(() => h.call("get_account", { accountId: IDS.accountOwned })));
    expect(lab.count("POST /lab/personas/SUPPORT/token")).toBe(1);
  });

  it("refreshes the token before it expires", async () => {
    let clock = 1_000_000;
    await start({ now: () => clock });
    lab.tokenTtl = 100; // seconds
    await h.call("get_account", { accountId: IDS.accountOwned });
    clock += 60_000; // 60 s later: 40 s left, still outside the 30 s margin
    await h.call("get_account", { accountId: IDS.accountOwned });
    expect(lab.count("POST /lab/personas/SUPPORT/token")).toBe(1);
    clock += 20_000; // 80 s in: 20 s left, inside the margin
    await h.call("get_account", { accountId: IDS.accountOwned });
    expect(lab.count("POST /lab/personas/SUPPORT/token")).toBe(2);
    const used = lab.requests.filter((r) => r.path === `/accounts/${IDS.accountOwned}`).map((r) => r.headers.authorization);
    expect(new Set(used).size).toBe(2);
  });

  it("gets a new token once after a 401 and then succeeds", async () => {
    await start();
    let first = true;
    lab.on(`GET /accounts/${IDS.accountOwned}`, () => {
      if (first) {
        first = false;
        return { status: 401, body: { status: 401, message: "expired" } };
      }
      return { body: { id: IDS.accountOwned, balance: 7, currency: "USD", ownerId: "o", createdAt: "x" } };
    });
    const r = await h.call("get_account", { accountId: IDS.accountOwned });
    expect(r.isError).toBeFalsy();
    expect(lab.count("POST /lab/personas/SUPPORT/token")).toBe(2);
  });

  it("gives up after one re-auth when the 401 persists", async () => {
    await start();
    lab.on(`GET /accounts/${IDS.accountOwned}`, { status: 401, body: { status: 401 } });
    const r = await h.call("get_account", { accountId: IDS.accountOwned });
    expect(r.isError).toBe(true);
    expect(lab.count(`GET /accounts/${IDS.accountOwned}`)).toBe(2);
  });
});

describe("the Lab's rate limit", () => {
  it("retries once after a 429, waits as told, and then succeeds", async () => {
    await start();
    let n = 0;
    lab.on("GET /lab/health", () => (++n === 1 ? { status: 429, headers: { "Retry-After": "2" }, body: { status: 429, message: "Too many" } } : { body: { checks: [{ name: "backend", status: "UP", latencyMs: 1 }] } }));
    const r = await h.call("get_system_status");
    expect(r.isError).toBeFalsy();
    expect(lab.count("GET /lab/health")).toBe(2);
    expect(h.sleeps).toEqual([2000]);
  });

  it("makes at most one retry and then reports a clear message with a retry hint", async () => {
    await start();
    lab.on("GET /lab/health", { status: 429, headers: { "Retry-After": "10" }, body: { status: 429, message: "Too many lab actions from this address" } });
    const r = await h.call("get_system_status");
    expect(r.isError).toBe(true);
    expect(lab.count("GET /lab/health")).toBe(2); // the first try and one retry, no loop
    const d = h.data(r) as any;
    expect(d.error).toBe("rate_limited");
    expect(d.retryAfterSeconds).toBe(10);
    expect(h.text(r)).toMatch(/Wait 10 seconds/);
    expect(h.text(r)).toMatch(/Do not retry in a loop/);
  });

  it("caps the wait at three seconds", async () => {
    await start();
    lab.on("GET /lab/health", { status: 429, headers: { "Retry-After": "60" } });
    await h.call("get_system_status");
    expect(h.sleeps).toEqual([3000]);
  });

  it("does not retry a write after a 503, because the work may have started", async () => {
    await start({ env: { MCP_ALLOW_WRITES: "true" } });
    lab.on("POST /reconciliation/runs", { status: 503, body: { status: 503, message: "overloaded" } });
    const r = await h.call("run_reconciliation");
    expect(r.isError).toBe(true);
    expect(lab.count("POST /reconciliation/runs")).toBe(1);
  });

  it("maps the Lab's read-only mode to a clear message", async () => {
    await start({ env: { MCP_ALLOW_WRITES: "true" } });
    lab.on("POST /reconciliation/runs", { status: 503, body: { status: 503, message: "The lab is in read-only mode: actions that change data are switched off." } });
    expect(h.data(await h.call("run_reconciliation")).error).toBe("read_only");
  });

  it("reports an unreachable Lab without a stack or an address", async () => {
    await start();
    await lab.stop();
    const r = await h.call("get_system_status");
    expect(r.isError).toBe(true);
    expect(h.data(r).error).toBe("unavailable");
    expect(h.text(r)).not.toMatch(/ECONNREFUSED|\n\s+at |\bat \S+\.(?:js|ts):\d+|127\.0\.0\.1/);
  });
});
