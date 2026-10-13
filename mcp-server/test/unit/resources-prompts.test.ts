import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { findDocsDir } from "../../src/resources.js";
import { connect, type Harness } from "../helpers/harness.js";
import { healthyLab, IDS, type FakeLab } from "../helpers/fakeLab.js";

let lab: FakeLab;
let h: Harness;

afterEach(async () => {
  await h?.close();
  await lab?.stop();
});

async function start(env: Record<string, string> = {}, docsDir?: string | null) {
  lab = healthyLab();
  await lab.start();
  lab.on("GET /v3/api-docs", { body: { openapi: "3.1.0", info: { title: "Settlement Engine API" }, paths: { "/settlements": {} } } });
  h = await connect(lab.baseUrl, { env, docsDir });
}

describe("resources", () => {
  it("serves the reconciliation and backend docs and the live OpenAPI document", async () => {
    await start();
    const list = (await h.client.listResources()).resources.map((r) => r.uri).sort();
    expect(list).toEqual(["settlement-engine://docs/backend", "settlement-engine://docs/reconciliation", "settlement-engine://openapi"]);

    const rec = await h.client.readResource({ uri: "settlement-engine://docs/reconciliation" });
    expect((rec.contents[0] as { text: string }).text).toMatch(/Reconciliation and Idempotency Engine/);
    const api = await h.client.readResource({ uri: "settlement-engine://openapi" });
    expect(JSON.parse((api.contents[0] as { text: string }).text).info.title).toBe("Settlement Engine API");
    expect(lab.count("GET /v3/api-docs")).toBe(1);
  });

  it("finds the repo docs when run from source", () => {
    expect(findDocsDir()).not.toBeNull();
  });

  it("says plainly when a doc is missing from an install", async () => {
    await start({}, mkdtempSync(join(tmpdir(), "no-docs-")));
    const r = await h.client.readResource({ uri: "settlement-engine://docs/backend" });
    expect((r.contents[0] as { text: string }).text).toMatch(/not available in this install/);
  });

  it("serves a doc from a given folder", async () => {
    const dir = mkdtempSync(join(tmpdir(), "docs-"));
    writeFileSync(join(dir, "backend.md"), "# Backend\nhello");
    await start({}, dir);
    const r = await h.client.readResource({ uri: "settlement-engine://docs/backend" });
    expect((r.contents[0] as { text: string }).text).toBe("# Backend\nhello");
  });
});

describe("prompts", () => {
  it("offers the two guided workflows", async () => {
    await start();
    expect((await h.client.listPrompts()).prompts.map((p) => p.name).sort()).toEqual(["explain-a-reconciliation-mismatch", "investigate-unknown-settlement"]);
  });

  it("names, in order, only tools that exist, and states the known gaps", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    const tools = new Set((await h.client.listTools()).tools.map((t) => t.name));
    for (const [name, args] of [
      ["investigate-unknown-settlement", { settlementId: IDS.settlementStranded }],
      ["investigate-unknown-settlement", {}],
      ["explain-a-reconciliation-mismatch", { mismatchId: IDS.mismatch }],
      ["explain-a-reconciliation-mismatch", {}],
    ] as const) {
      const p = await h.client.getPrompt({ name, arguments: args });
      const text = (p.messages[0]!.content as { text: string }).text;
      const mentioned = [...text.matchAll(/\b([a-z]+(?:_[a-z]+)+)\b/g)].map((m) => m[1]!).filter((w) => /^(get|list|explain|run|resolve|create)_/.test(w));
      expect(mentioned.length, name).toBeGreaterThan(2);
      for (const t of mentioned) expect(tools.has(t), `${name} mentions ${t}`).toBe(true);
    }
    const investigate = (await h.client.getPrompt({ name: "investigate-unknown-settlement", arguments: {} })).messages[0]!.content as { text: string };
    expect(investigate.text).toMatch(/Known gap 1/);
    const order = ["get_system_status", "get_reconciliation_summary", "get_settlement", "explain_state_machine"].map((t) => investigate.text.indexOf(t));
    expect([...order].sort((a, b) => a - b)).toEqual(order);
    const explain = (await h.client.getPrompt({ name: "explain-a-reconciliation-mismatch", arguments: {} })).messages[0]!.content as { text: string };
    expect(explain.text).toMatch(/Known gap 2/);
    expect(explain.text).toMatch(/Do not call resolve_settlement_mismatch unless/);
  });
});

describe("tool descriptions only point at tools that exist", () => {
  it("every tool named after 'Next:' is registered", async () => {
    await start({ MCP_ALLOW_WRITES: "true" });
    const tools = (await h.client.listTools()).tools;
    const names = new Set(tools.map((t) => t.name));
    for (const t of tools) {
      for (const m of (t.description ?? "").matchAll(/\b((?:get|list|explain|run|resolve|create)_[a-z_]+)\b/g)) {
        expect(names.has(m[1]!), `${t.name} mentions ${m[1]}`).toBe(true);
      }
    }
  });
});

describe("an unexpected failure inside a tool", () => {
  it("returns a generic message and never the internal error", async () => {
    await start();
    lab.on("GET /lab/health", { raw: "{not json" }); // parsed as null, so the tool's own code throws on it
    const r = await h.call("get_system_status");
    expect(r.isError).toBe(true);
    expect(JSON.stringify(r)).not.toMatch(/TypeError|undefined|Cannot read/);
  });
});
