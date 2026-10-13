#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { loadConfig, withLabMode } from "./config.js";
import { StartupRefusal, ToolError } from "./errors.js";
import { LabClient } from "./http.js";
import { createLogger } from "./log.js";
import { assertNotForbidden } from "./safety.js";
import { createServer } from "./server.js";

async function main(): Promise<void> {
  const log = createLogger();
  const requested = loadConfig();

  // Safety rule 2: refuse the staging host before a single request is made.
  await assertNotForbidden(requested.baseUrl);

  const client = new LabClient(requested, log);

  // Safety rule 1: the target must answer as a Lab sandbox.
  let labStatus: Record<string, unknown>;
  try {
    labStatus = await client.fetchLabStatus();
  } catch (err) {
    if (err instanceof ToolError) {
      throw new StartupRefusal(`Refusing to start: ${err.message} Target: ${requested.baseUrl.origin}.`);
    }
    throw err;
  }

  const mode = withLabMode(requested, labStatus);
  if (mode.writesDroppedBecauseLabReadOnly) log.warn("writes_disabled", { reason: "The Lab is in read-only mode, so the write tools are not registered." });
  const config = mode.config;

  const { server, tools } = createServer({ client, config, log });
  await server.connect(new StdioServerTransport());
  log.info("started", { target: config.baseUrl.origin, writes: config.allowWrites, tools: tools.length });
}

main().catch((err: unknown) => {
  const message = err instanceof Error ? err.message : "unknown error";
  process.stderr.write(`settlement-engine-mcp: ${message}\n`);
  process.exit(1);
});
