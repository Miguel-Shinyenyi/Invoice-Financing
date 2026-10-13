/**
 * A pass-through MCP server for the claude-cli eval mode. It starts the real server and forwards
 * every call. With EVAL_TIMEOUT_TOOL set, the first call to that tool runs for real and the agent
 * is told it timed out, which is how a lost response looks (same as the API-mode loop).
 */
import { resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { TIMEOUT_TEXT } from "./agent.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";

const root = resolve(import.meta.dirname, "..");

const upstream = new Client({ name: "eval-proxy", version: "0" });
await upstream.connect(
  new StdioClientTransport({
    command: resolve(root, "node_modules", ".bin", "tsx"),
    args: [resolve(root, "src", "index.ts")],
    env: process.env as Record<string, string>,
    stderr: "ignore",
  }),
);

let pendingTimeout = process.env.EVAL_TIMEOUT_TOOL || undefined;

const server = new Server({ name: "eval-proxy", version: "0" }, { capabilities: { tools: {} } });
server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: (await upstream.listTools()).tools }));
server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const out = await upstream.callTool({ name: req.params.name, arguments: req.params.arguments });
  if (pendingTimeout !== undefined && req.params.name === pendingTimeout) {
    pendingTimeout = undefined;
    return { isError: true, content: [{ type: "text", text: TIMEOUT_TEXT }] };
  }
  return out as never;
});
await server.connect(new StdioServerTransport());
