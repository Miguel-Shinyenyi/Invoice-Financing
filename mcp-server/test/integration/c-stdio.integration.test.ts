import { resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { describe, expect, it } from "vitest";
import { labReachable, LIVE_URL, SKIP_NOTE } from "../helpers/live.js";

const up = await labReachable();
if (!up) process.stderr.write(`\n[integration] ${SKIP_NOTE}\n`);

const root = resolve(import.meta.dirname, "..", "..");

async function withServer<T>(env: Record<string, string>, fn: (c: Client) => Promise<T>): Promise<T> {
  const transport = new StdioClientTransport({
    command: resolve(root, "node_modules", ".bin", "tsx"),
    args: ["src/index.ts"],
    cwd: root,
    env: { ...(process.env as Record<string, string>), LAB_BASE_URL: LIVE_URL, ...env },
    stderr: "pipe",
  });
  const client = new Client({ name: "stdio-test", version: "0" });
  await client.connect(transport);
  try {
    return await fn(client);
  } finally {
    await client.close();
  }
}

describe.skipIf(!up)("live Lab: the real server over stdio", () => {
  it("speaks MCP over stdio, read-only by default", async () => {
    const out = await withServer({ MCP_ALLOW_WRITES: "" }, async (c) => {
      const tools = (await c.listTools()).tools.map((t) => t.name);
      const status = (await c.callTool({ name: "get_system_status", arguments: {} })) as { content: { text: string }[] };
      return { tools, text: status.content[0]!.text };
    });
    expect(out.tools).toHaveLength(10);
    expect(out.tools).not.toContain("create_settlement");
    expect(out.text).toMatch(/^Lab sandbox is /);
  });

  it("registers the write tools over stdio when MCP_ALLOW_WRITES=true", async () => {
    const tools = await withServer({ MCP_ALLOW_WRITES: "true" }, async (c) => (await c.listTools()).tools.map((t) => t.name));
    expect(tools).toHaveLength(13);
    expect(tools).toContain("create_settlement");
  });
});
