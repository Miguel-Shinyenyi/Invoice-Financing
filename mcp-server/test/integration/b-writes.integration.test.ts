import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { DUP_PAIR, HAS_DUPLICATE_BURST_SCENARIO, OPEN_MISMATCH, accounts } from "../../support/seed.js";
import { browse, labReachable, liveClient, SKIP_NOTE } from "../helpers/live.js";

const up = await labReachable();
if (!up) process.stderr.write(`\n[integration] ${SKIP_NOTE}\n`);

const num = (v: unknown) => Number(v);

describe.skipIf(!up)("live Lab: write tools (MCP_ALLOW_WRITES=true)", () => {
  let c: Awaited<ReturnType<typeof liveClient>>;

  beforeAll(async () => {
    c = await liveClient(true);
  });
  afterAll(async () => c?.close());

  it("registers the three write tools only when writes are on", async () => {
    const names = (await c.client.listTools()).tools.map((t) => t.name);
    expect(names).toEqual(expect.arrayContaining(["create_settlement", "run_reconciliation", "resolve_settlement_mismatch"]));
    expect(names).toHaveLength(13);
  });

  it("create_settlement twice with one key: one settlement exists, both calls return its id, money moved once", async () => {
    expect(HAS_DUPLICATE_BURST_SCENARIO).toBe(true);
    const [src, dst] = DUP_PAIR as [string, string];
    expect(accounts.find((a) => a.id === src)?.opening).toBe(1000); // the seed's duplicate-key pair is 1000 / 0
    expect(accounts.find((a) => a.id === dst)?.opening).toBe(0);

    const balance = async (id: string) => num((await browse("ledger_accounts")).find((r) => r.id === id)!.balance);
    const [srcBefore, dstBefore] = [await balance(src), await balance(dst)];

    const key = randomUUID();
    const args = { sourceAccountId: src, destinationAccountId: dst, amount: "10.00", currency: "USD", idempotencyKey: key };
    const first = await c.call("create_settlement", args);
    const second = await c.call("create_settlement", args); // the retry after a timeout
    expect(first.isError, first.text).toBe(false);
    expect(second.isError, second.text).toBe(false);
    expect(second.data.settlementId).toBe(first.data.settlementId);
    expect(second.data.status).toBe(first.data.status);

    // Independent check through the Lab's data browser, not through the MCP server.
    const withKey = (await browse("settlements", 2)).filter((r) => r.idempotency_key === key);
    expect(withKey).toHaveLength(1);
    expect(withKey[0]!.id).toBe(first.data.settlementId);

    // The pair moved by exactly one settlement's amount, if it confirmed.
    expect(first.data.status).toBe("CONFIRMED");
    expect(await balance(src)).toBe(srcBefore - 10);
    expect(await balance(dst)).toBe(dstBefore + 10);
  });

  it("the same key with a different body is a conflict, and nothing new is created", async () => {
    const [src, dst] = DUP_PAIR as [string, string];
    const key = randomUUID();
    const base = { sourceAccountId: src, destinationAccountId: dst, currency: "USD", idempotencyKey: key };
    const ok = await c.call("create_settlement", { ...base, amount: "1.00" });
    expect(ok.isError).toBe(false);
    const bad = await c.call("create_settlement", { ...base, amount: "2.00" });
    expect(bad.isError).toBe(true);
    expect(bad.data.error).toBe("conflict");
    expect((await browse("settlements", 2)).filter((r) => r.idempotency_key === key)).toHaveLength(1);
  });

  it("run_reconciliation completes and reports what it checked", async () => {
    const r = await c.call("run_reconciliation");
    expect(r.isError, r.text).toBe(false);
    expect(r.data.status).toBe("COMPLETED");
    expect(r.data.recordsChecked).toBeGreaterThan(0);
  });

  it("resolve_settlement_mismatch records a resolution and changes nothing else (Known gap 2)", async () => {
    const open = await c.call("list_open_reconciliation_mismatches");
    const target = open.data.items.find((m: any) => m.settlementId === OPEN_MISMATCH.settlementId);
    expect(target, "an open mismatch for the seeded settlement").toBeDefined();
    const before = await c.call("get_settlement", { settlementId: OPEN_MISMATCH.settlementId });

    const r = await c.call("resolve_settlement_mismatch", { mismatchId: target.mismatchId, reason: "integration test: external record checked by hand" });
    expect(r.isError, r.text).toBe(false);
    expect(r.data.resolutionStatus).toBe("RESOLVED");
    expect(r.data.settlementChanged).toBe(false);

    const after = await c.call("get_settlement", { settlementId: OPEN_MISMATCH.settlementId });
    expect(after.data.settlement).toEqual(before.data.settlement); // same status, amount and updatedAt
    expect(after.data.ledgerEntries).toEqual(before.data.ledgerEntries);

    const again = await c.call("resolve_settlement_mismatch", { mismatchId: target.mismatchId, reason: "second time" });
    expect(again.isError).toBe(true);
    expect(again.data.error).toBe("conflict");
  });
});
