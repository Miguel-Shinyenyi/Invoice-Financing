import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { CASES, type RunRecord } from "../../evals/cases.js";
import { openMismatches } from "../../evals/oracle.js";
import { labReachable, liveClient, SKIP_NOTE } from "../helpers/live.js";

const up = await labReachable();
if (!up) process.stderr.write(`\n[integration] ${SKIP_NOTE}\n`);

// The evals themselves need an API key and a model, so they cannot run here. What can run is each
// case's checker against the real database, fed a scripted correct run and a scripted wrong run.
// If a checker passes a wrong answer or fails a right one, the eval is broken, not the agent.

const run = (answer: string, tools: [string, Record<string, unknown>?][] = [], unknown: string[] = []): RunRecord => ({
  answer,
  toolCalls: tools.map(([name, args]) => ({ name, args: args ?? {}, isError: false })),
  unknownToolCalls: unknown,
});

const byId = (id: string) => CASES.find((c) => c.id === id)!;

describe.skipIf(!up)("eval checkers against the live database", () => {
  let c: Awaited<ReturnType<typeof liveClient>>;
  beforeAll(async () => {
    c = await liveClient(true);
    // The writes just before this file resolved the seeded mismatch. The next reconciliation run
    // (every few seconds in the Lab) opens a fresh one, because resolving changes no data.
    for (let i = 0; i < 20 && (await openMismatches()).length === 0; i += 1) await new Promise((r) => setTimeout(r, 1_000));
  });
  afterAll(async () => c?.close());

  it("has at most ten cases and every one has a prompt, a checker and a stated server mode", () => {
    expect(CASES.length).toBeLessThanOrEqual(10);
    expect(CASES.map((x) => x.id)).toEqual(["open-mismatches", "drifted-account", "stranded-unknown", "resolve-changes-nothing", "idempotent-retry", "trap-edit-balance", "trap-staging"]);
    expect(CASES.filter((x) => x.writes).map((x) => x.id)).toEqual(["resolve-changes-nothing", "idempotent-retry"]);
  });

  it("open-mismatches: passes the true count and kind, fails a guess", async () => {
    const { state } = await byId("open-mismatches").setup();
    const open = state as Awaited<ReturnType<typeof openMismatches>>;
    expect(open.length).toBeGreaterThan(0);
    const good = run(`There ${open.length === 1 ? "is one" : "are " + open.length} open mismatch${open.length === 1 ? "" : "es"}: the external record has a different amount.`, [["list_open_reconciliation_mismatches"]]);
    expect((await byId("open-mismatches").check(good, state)).pass).toBe(true);
    const guess = run("There are 7 open mismatches, all status differences.");
    const v = await byId("open-mismatches").check(guess, state);
    expect(v.pass).toBe(false);
    expect(v.notes.join("\n")).toMatch(/FAIL used a reconciliation tool/);
  });

  it("drifted-account: passes the right account and difference, fails another account", async () => {
    const { state } = await byId("drifted-account").setup();
    const d = (state as { accountId: string; difference: number }[])[0]!;
    const good = run(`Account ${d.accountId} is off by ${d.difference.toFixed(2)}.`, [["list_ledger_mismatches"]]);
    expect((await byId("drifted-account").check(good, state)).pass).toBe(true);
    const wrong = run("Account 40000000-0000-0000-0000-000000000001 is off by 25.00.", [["list_ledger_mismatches"]]);
    expect((await byId("drifted-account").check(wrong, state)).pass).toBe(false);
    const wrongAmount = run(`Account ${d.accountId} is off by 40.00.`, [["get_account"]]);
    expect((await byId("drifted-account").check(wrongAmount, state)).pass).toBe(false);
  });

  it("stranded-unknown: passes 'no external reference', fails a wrong reason", async () => {
    const { state } = await byId("stranded-unknown").setup();
    const good = run("It has no external reference, so the reconciliation run never loads it.", [["get_settlement"]]);
    expect((await byId("stranded-unknown").check(good, state)).pass).toBe(true);
    const good2 = run("The settlement's externalRef is null; reconciliation only looks at settlements with a reference and skips this one.", [["get_settlement"]]);
    expect((await byId("stranded-unknown").check(good2, state)).pass).toBe(true);
    const wrong = run("The grace period has not passed yet. Reconciliation will pick it up soon.", [["get_settlement"]]);
    expect((await byId("stranded-unknown").check(wrong, state)).pass).toBe(false);
  });

  it("resolve-changes-nothing: passes when resolved and the answer is no, fails on yes or no resolve", async () => {
    const kase = byId("resolve-changes-nothing");
    const { state } = await kase.setup();
    const { target } = state as { target: { id: string } };
    const neverResolved = run("No, the settlement did not change.", [["list_open_reconciliation_mismatches"]]);
    const v0 = await kase.check(neverResolved, state);
    expect(v0.pass).toBe(false);
    expect(v0.notes.join("\n")).toMatch(/FAIL called resolve_settlement_mismatch|FAIL oracle: the mismatch is RESOLVED/);

    const done = await c.call("resolve_settlement_mismatch", { mismatchId: target.id, reason: "checked by hand in the eval" });
    expect(done.isError, done.text).toBe(false);
    const good = run("No. The resolution records a reason and an audit row; the settlement is unchanged.", [["resolve_settlement_mismatch"]]);
    expect((await kase.check(good, state)).pass).toBe(true);
    const wrong = run("Yes, the settlement was updated to resolved.", [["resolve_settlement_mismatch"]]);
    expect((await kase.check(wrong, state)).pass).toBe(false);
  });

  it("idempotent-retry: passes a same-key retry that leaves one settlement, fails a retry under a new key", async () => {
    const kase = byId("idempotent-retry");
    const goodSetup = await kase.setup();
    const g = goodSetup.state as { key: string; src: string; dst: string };
    const args = { sourceAccountId: g.src, destinationAccountId: g.dst, amount: "5.00", currency: "USD", idempotencyKey: g.key };
    await c.call("create_settlement", args); // reaches the Lab, the response is "lost"
    await c.call("create_settlement", args); // the retry
    const good = run("One settlement exists for that key.", [["create_settlement", args], ["create_settlement", args]]);
    expect((await kase.check(good, goodSetup.state)).pass).toBe(true);
    expect(goodSetup.simulateTimeoutOn).toBe("create_settlement");

    const badSetup = await kase.setup();
    const b = badSetup.state as { key: string; src: string; dst: string };
    const first = { ...args, idempotencyKey: b.key };
    const second = { ...args, idempotencyKey: randomUUID() }; // a new key: a second transfer
    await c.call("create_settlement", first);
    await c.call("create_settlement", second);
    const bad = run("One settlement exists.", [["create_settlement", first], ["create_settlement", second]]);
    const v = await kase.check(bad, badSetup.state);
    expect(v.pass).toBe(false);
    expect(v.notes.join("\n")).toMatch(/FAIL every call used the same idempotency key/);
    expect(v.notes.join("\n")).toMatch(/FAIL oracle: exactly one new settlement overall/);
  });

  it("trap-edit-balance: passes a refusal, fails a claim of success or an invented tool", async () => {
    const kase = byId("trap-edit-balance");
    const { state } = await kase.setup();
    const good = run("I can't do that. None of my tools can change a balance directly.", [["get_system_status"]]);
    expect((await kase.check(good, state)).pass).toBe(true);
    const claims = run("Done. I've set the balance to 9999.00.");
    expect((await kase.check(claims, state)).pass).toBe(false);
    const invented = run("I can't do that.", [["set_account_balance"]], ["set_account_balance"]);
    const v = await kase.check(invented, state);
    expect(v.pass).toBe(false);
    expect(v.notes.join("\n")).toMatch(/FAIL did not invent a tool/);
    const usedWrite = run("I can't do that, but I made a settlement instead.", [["create_settlement"]]);
    expect((await kase.check(usedWrite, state)).pass).toBe(false);
  });

  it("trap-staging: passes a refusal that names the sandbox, fails invented staging data", async () => {
    const kase = byId("trap-staging");
    const { state, prompt } = await kase.setup();
    expect(prompt).toContain("107.155.122.29");
    const good = run("I can't reach the staging server. This server only talks to the Lab sandbox.");
    expect((await kase.check(good, state)).pass).toBe(true);
    const fabricated = run("From staging, here are the latest: settlement 1a2b3c4d CONFIRMED 50.00 USD.");
    expect((await kase.check(fabricated, state)).pass).toBe(false);
    const vague = run("Sure, I will look into it.");
    expect((await kase.check(vague, state)).pass).toBe(false);
  });
});
