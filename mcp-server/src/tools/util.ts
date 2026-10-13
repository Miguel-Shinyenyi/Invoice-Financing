import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult, ToolAnnotations } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import type { Config } from "../config.js";
import { ToolError } from "../errors.js";
import { fail, ok, type Shaped } from "../format.js";
import type { LabClient } from "../http.js";
import type { Logger } from "../log.js";

export interface ToolContext {
  client: LabClient;
  config: Config;
  log: Logger;
  /** Filled by defineTool with each tool registered on this server. */
  registered: { name: string; kind: Kind }[];
}

export type Kind = "read" | "write";

export interface ToolDef<S extends z.ZodRawShape> {
  name: string;
  kind: Kind;
  title: string;
  description: string;
  input: S;
  annotations: ToolAnnotations;
  run: (args: z.infer<z.ZodObject<S>>, ctx: ToolContext) => Promise<Shaped>;
}

export function defineTool<S extends z.ZodRawShape>(server: McpServer, ctx: ToolContext, def: ToolDef<S>): void {
  ctx.registered.push({ name: def.name, kind: def.kind });
  const handler = async (args: unknown): Promise<CallToolResult> => {
    {
      const started = Date.now();
      try {
        const shaped = await def.run(args as z.infer<z.ZodObject<S>>, ctx);
        ctx.log.info("tool", { tool: def.name, outcome: "ok", ms: Date.now() - started });
        return ok(shaped, ctx.config);
      } catch (err) {
        if (err instanceof ToolError) {
          ctx.log.info("tool", { tool: def.name, outcome: err.code, ms: Date.now() - started });
          return fail(err);
        }
        // Unknown failure: log the message only (the logger redacts it), tell the agent nothing internal.
        ctx.log.warn("tool_crash", { tool: def.name, message: err instanceof Error ? err.message : "unknown" });
        return fail(new ToolError("upstream_error", "The MCP server hit an unexpected problem handling this call."));
      }
    }
  };
  // The SDK types the callback from the zod shape. The shape is validated by the SDK before the
  // handler runs, so the cast only bridges the generic parameter.
  server.registerTool(
    def.name,
    { title: def.title, description: def.description, inputSchema: def.input, annotations: def.annotations },
    handler as never,
  );
}

/** Seed ids are not RFC 4122 UUIDs (version digit 0), so use the loose GUID shape. */
export const guid = (what: string) => z.guid().describe(`${what}, a UUID`);

export const limitField = z
  .number()
  .int()
  .min(1)
  .max(100)
  .optional()
  .describe("Rows to return. Default 20, maximum 100. A longer list is cut and the result says how many were left out.");

export const pageField = z
  .number()
  .int()
  .min(0)
  .max(10_000)
  .optional()
  .describe("Zero-based page number. Default 0.");

export const READ_ANNOTATIONS: ToolAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false,
};
