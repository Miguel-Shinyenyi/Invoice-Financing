#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { loadConfig } from "./config.js";
import { StartupRefusal, ToolError } from "./errors.js";
import { LabClient } from "./http.js";
import { createLogger } from "./log.js";
import { assertNotForbidden } from "./safety.js";
import { createServer } from "./server.js";

async function main(): Promise<void> {
  const log = createLogger();
  const config = loadConfig();

  // Safety rule 2: refuse the staging host before a single request is made.
  await assertNotForbidden(config.baseUrl);

  const client = new LabClient(config, log);

  // Safety rule 1: the target must answer as a Lab sandbox.
  try {
    await client.fetchLabStatus();
  } catch (err) {
    if (err instanceof ToolError) {
      throw new StartupRefusal(`Refusing to start: ${err.message} Target: ${config.baseUrl.origin}.`);
    }
    throw err;
  }

  const { server, tools } = createServer({ client, config, log });
  await server.connect(new StdioServerTransport());
  log.info("started", { target: config.baseUrl.origin, writes: config.allowWrites, tools: tools.length });
}

main().catch((err: unknown) => {
  const message = err instanceof Error ? err.message : "unknown error";
  process.stderr.write(`settlement-engine-mcp: ${message}\n`);
  process.exit(1);
});
