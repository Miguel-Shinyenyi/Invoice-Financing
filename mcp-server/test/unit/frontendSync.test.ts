import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { connect, type Harness } from "../helpers/harness.js";
import { healthyLab, type FakeLab } from "../helpers/fakeLab.js";

// The Lab UI's Agent page lists the tools from frontend/lib/lab/mcpTools.ts. Same rule as the forbidden hosts:
// a copy is allowed only if a test proves it matches.
const FILE = resolve(import.meta.dirname, "../../../frontend/lib/lab/mcpTools.ts");

let lab: FakeLab;
let h: Harness;
afterEach(async () => {
  await h?.close();
  await lab?.stop();
});

describe("frontend/lib/lab/mcpTools.ts matches the registered tools", () => {
  it("has the same tool names and the same read/write kind", async () => {
    lab = healthyLab();
    await lab.start();
    h = await connect(lab.baseUrl, { env: { MCP_ALLOW_WRITES: "true" } });
    const registered = new Map<string, string>();
    // The server's own registry knows each tool's kind. The wire listing only has names, so read both.
    for (const t of h.built.tools) registered.set(t.name, t.kind);
    const listed = (await h.client.listTools()).tools.map((t) => t.name);
    expect([...registered.keys()].sort()).toEqual([...listed].sort());

    const source = readFileSync(FILE, "utf8");
    const fromFrontend = new Map<string, string>();
    for (const m of source.matchAll(/name:\s*"([a-z_]+)",\s*kind:\s*"(read|write)"/g)) fromFrontend.set(m[1]!, m[2]!);

    expect([...fromFrontend.entries()].sort()).toEqual([...registered.entries()].sort());
  });
});
