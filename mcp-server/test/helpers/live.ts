import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { loadConfig } from "../../src/config.js";
import { LabClient } from "../../src/http.js";
import { createLogger } from "../../src/log.js";
import { createServer } from "../../src/server.js";

import { LIVE_URL } from "../../support/labData.js";

export { browse, LIVE_URL, resetLab } from "../../support/labData.js";

export async function labReachable(): Promise<boolean> {
  try {
    const res = await fetch(new URL("/lab/status", LIVE_URL), { signal: AbortSignal.timeout(2_000) });
    if (res.status !== 200) return false;
    const body = (await res.json()) as { faultStatement?: unknown };
    return typeof body.faultStatement === "string";
  } catch {
    return false;
  }
}

export const SKIP_NOTE =
  `Lab sandbox not reachable at ${LIVE_URL}. Integration tests skipped. ` +
  "Start it with: docker compose -f infra/docker-compose.lab.yml up -d";

export async function liveClient(allowWrites: boolean, readPersona: "SUPPORT" | "READ_ONLY" = "SUPPORT") {
  const config = loadConfig({ LAB_BASE_URL: LIVE_URL, MCP_ALLOW_WRITES: allowWrites ? "true" : "false", MCP_READ_PERSONA: readPersona });
  const log = createLogger(() => {});
  const labClient = new LabClient(config, log);
  const built = createServer({ client: labClient, config, log });
  const [a, b] = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: "live-test", version: "0" });
  await Promise.all([built.server.connect(a), client.connect(b)]);
  return {
    client,
    async call(name: string, args: Record<string, unknown> = {}) {
      const r = (await client.callTool({ name, arguments: args })) as CallToolResult;
      return { raw: r, data: r.structuredContent as Record<string, any>, text: (r.content[0] as { text: string }).text, isError: r.isError === true };
    },
    async close() {
      await client.close();
      await built.server.close();
    },
  };
}
