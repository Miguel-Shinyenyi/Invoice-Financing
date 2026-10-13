import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { Config } from "./config.js";
import type { LabClient } from "./http.js";
import type { Logger } from "./log.js";
import { registerPrompts } from "./prompts.js";
import { registerResources } from "./resources.js";
import { registerReadTools } from "./tools/read.js";
import type { Kind } from "./tools/util.js";
import { registerWriteTools } from "./tools/write.js";

export const SERVER_NAME = "settlement-engine-lab";
export const SERVER_VERSION = "0.1.0";

export interface BuiltServer {
  server: McpServer;
  tools: { name: string; kind: Kind }[];
}

/**
 * Builds the server. Write tools are not registered at all unless allowWrites is set, so a client
 * cannot see or call them by default.
 */
export function createServer(deps: { client: LabClient; config: Config; log: Logger; docsDir?: string | null }): BuiltServer {
  const server = new McpServer(
    { name: SERVER_NAME, version: SERVER_VERSION },
    {
      instructions:
        "Tools over the settlement engine's Lab sandbox: a separate stack with seeded fake data, never staging. Start with get_system_status. " +
        "Reads are safe. Write tools exist only when the operator set MCP_ALLOW_WRITES=true. The engine's idempotency, reconciliation and known gaps are explained in the docs resources.",
    },
  );
  const ctx = { client: deps.client, config: deps.config, log: deps.log, registered: [] as { name: string; kind: Kind }[] };
  registerReadTools(server, ctx);
  if (deps.config.allowWrites) registerWriteTools(server, ctx);
  registerResources(server, deps.client, deps.docsDir);
  registerPrompts(server);
  return { server, tools: ctx.registered };
}
