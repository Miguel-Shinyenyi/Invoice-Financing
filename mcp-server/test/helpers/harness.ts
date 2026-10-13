import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { loadConfig, type Config } from "../../src/config.js";
import { LabClient } from "../../src/http.js";
import { createLogger } from "../../src/log.js";
import { createServer, type BuiltServer } from "../../src/server.js";

export interface Harness {
  client: Client;
  built: BuiltServer;
  logs: string[];
  config: Config;
  labClient: LabClient;
  sleeps: number[];
  call(name: string, args?: Record<string, unknown>): Promise<CallToolResult>;
  text(result: CallToolResult): string;
  data(result: CallToolResult): Record<string, unknown>;
  close(): Promise<void>;
}

export interface HarnessOptions {
  env?: Record<string, string | undefined>;
  now?: () => number;
  docsDir?: string | null;
}

/** An MCP client wired to the real server over an in-memory transport, pointed at a fake Lab. */
export async function connect(baseUrl: string, options: HarnessOptions = {}): Promise<Harness> {
  const config = loadConfig({ LAB_BASE_URL: baseUrl, ...(options.env ?? {}) });
  const logs: string[] = [];
  const sleeps: number[] = [];
  const log = createLogger((line) => logs.push(line));
  const labClient = new LabClient(config, log, {
    sleep: async (ms) => {
      sleeps.push(ms);
    },
    now: options.now,
  });
  const built = createServer({ client: labClient, config, log, docsDir: options.docsDir });
  const [a, b] = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: "test", version: "0" });
  await Promise.all([built.server.connect(a), client.connect(b)]);
  return {
    client,
    built,
    logs,
    config,
    labClient,
    sleeps,
    call: async (name, args = {}) => (await client.callTool({ name, arguments: args })) as CallToolResult,
    text: (r) => (r.content[0] as { text: string }).text,
    data: (r) => r.structuredContent as Record<string, unknown>,
    close: async () => {
      await client.close();
      await built.server.close();
    },
  };
}
