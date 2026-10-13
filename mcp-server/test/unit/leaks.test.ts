import { afterEach, describe, expect, it } from "vitest";
import { createLogger, redact } from "../../src/log.js";
import { connect, type Harness } from "../helpers/harness.js";
import { healthyLab, IDS, SANDBOX_PASSWORD, type FakeLab } from "../helpers/fakeLab.js";

let lab: FakeLab;
let h: Harness;

afterEach(async () => {
  await h?.close();
  await lab?.stop();
});

const STACK = "java.lang.IllegalStateException: boom\n\tat com.settlementengine.core.SettlementService.create(SettlementService.java:88)";

describe("no token, password, stack or raw upstream body ever reaches the agent or a log", () => {
  async function exercise() {
    lab = healthyLab();
    await lab.start();
    lab.on(`GET /accounts/${IDS.accountOwned}`, { body: { id: IDS.accountOwned, balance: 1, currency: "USD", ownerId: "o", createdAt: "x" } });
    lab.on(`GET /invoices/${IDS.invoice}`, { status: 500, body: { status: 500, message: STACK, trace: STACK, internal: "SECRET-INTERNAL-FIELD" } });
    lab.on(`GET /lab/settlements/${IDS.settlementStranded}/inspect`, { status: 400, body: { status: 400, message: "Method parameter 'id': Failed to convert value of type 'java.lang.String' to required type 'java.util.UUID'" } });
    h = await connect(lab.baseUrl, { env: { MCP_ALLOW_WRITES: "true" } });
    const results = [
      await h.call("get_system_status"),
      await h.call("get_account", { accountId: IDS.accountOwned }),
      await h.call("get_invoice", { invoiceId: IDS.invoice }),
      await h.call("get_settlement", { settlementId: IDS.settlementStranded }),
      await h.call("list_open_reconciliation_mismatches"),
      await h.call("run_reconciliation"),
    ];
    return results;
  }

  it("keeps tokens out of every result and every log line", async () => {
    const results = await exercise();
    const issued = lab.requests.filter((r) => r.path.startsWith("/lab/personas/") && r.method === "POST");
    expect(issued.length).toBeGreaterThan(0);
    // Collect the tokens the fake actually issued, from the Authorization headers that were sent.
    const tokens = new Set(lab.requests.map((r) => String(r.headers.authorization ?? "").replace("Bearer ", "")).filter((t) => t.length > 20));
    expect(tokens.size).toBeGreaterThan(0);
    const everything = JSON.stringify(results) + h.logs.join("\n");
    for (const t of tokens) {
      expect(everything).not.toContain(t);
      expect(everything).not.toContain(t.split(".")[1]!);
    }
    expect(everything).not.toMatch(/Bearer\s/i);
    expect(everything).not.toMatch(/eyJ[A-Za-z0-9_-]{6,}\./);
  });

  it("keeps the sandbox passwords out", async () => {
    const results = await exercise();
    const everything = JSON.stringify(results) + h.logs.join("\n");
    expect(everything).not.toContain(SANDBOX_PASSWORD);
    expect(everything).not.toContain("lab-readonly-sandbox");
  });

  it("keeps stack traces, class names and unknown upstream fields out of errors", async () => {
    const results = await exercise();
    const everything = JSON.stringify(results);
    expect(everything).not.toContain("SettlementService.java");
    expect(everything).not.toContain("IllegalStateException");
    expect(everything).not.toContain("SECRET-INTERNAL-FIELD");
    expect(everything).not.toContain("java.util.UUID");
    expect(everything).not.toContain("com.settlementengine");
  });

  it("writes log lines as metadata only: no bodies, no query strings", async () => {
    await exercise();
    for (const line of h.logs) {
      const o = JSON.parse(line) as Record<string, unknown>;
      expect(Object.keys(o).sort().join()).toMatch(/event|level/);
      expect(line).not.toMatch(/\?/); // no query string in a path
    }
    expect(h.logs.some((l) => l.includes('"event":"request"'))).toBe(true);
  });
});

describe("redact", () => {
  it("removes JWTs and bearer headers from any text", () => {
    const jwt = "eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiIwMDAwMDAwMCJ9.abcDEF123_-xyz";
    expect(redact(`token ${jwt} here`)).toBe("token [redacted-token] here");
    expect(redact("Authorization: Bearer abc.def-ghi")).toBe("Authorization: Bearer [redacted]");
  });

  it("redacts inside structured log fields", () => {
    const lines: string[] = [];
    createLogger((l) => lines.push(l)).info("x", { note: "got eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiIwMDAwMDAwMCJ9.abcDEF123 back" });
    expect(lines[0]).not.toContain("eyJ");
  });
});
