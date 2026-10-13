import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { LIMITS, SYSTEM, type AgentResult } from "./agent.js";

const root = resolve(import.meta.dirname, "..");
const SERVER_NAME = "lab";
const PREFIX = `mcp__${SERVER_NAME}__`;
const CASE_TIMEOUT_MS = 240_000;

/** The CLI could not run the case (not logged in, crashed, timed out). Says nothing about the tools. */
export class AgentRunError extends Error {}

export interface CliResult extends AgentResult {
  model: string;
  /** What the call would cost at API prices. A subscription is not billed per token. */
  notionalUsd: number;
}

/** Turns the CLI's `--output-format stream-json` lines into the record the checkers read. */
export function parseStream(lines: string[]): CliResult {
  const record: CliResult = {
    answer: "", toolCalls: [], unknownToolCalls: [], turns: 0, inputTokens: 0, outputTokens: 0, stopReason: "answered", model: "unknown", notionalUsd: 0,
  };
  let known = new Set<string>();
  const byId = new Map<string, number>();
  let finished = false;

  for (const line of lines) {
    let e: any;
    try {
      e = JSON.parse(line);
    } catch {
      continue; // warnings and other non-JSON lines
    }
    if (e.type === "system" && e.subtype === "init") {
      record.model = String(e.model ?? "unknown");
      known = new Set<string>(e.tools ?? []);
      const server = (e.mcp_servers ?? []).find((s: any) => s.name === SERVER_NAME);
      if (!server || server.status !== "connected") throw new AgentRunError(`The MCP server did not connect (status: ${server?.status ?? "missing"}).`);
    } else if (e.type === "assistant" && Array.isArray(e.message?.content)) {
      for (const b of e.message.content) {
        if (b.type !== "tool_use") continue;
        const name = String(b.name).startsWith(PREFIX) ? String(b.name).slice(PREFIX.length) : String(b.name);
        if (!known.has(b.name)) record.unknownToolCalls.push(name);
        byId.set(b.id, record.toolCalls.length);
        record.toolCalls.push({ name, args: (b.input ?? {}) as Record<string, unknown>, isError: !known.has(b.name) });
      }
    } else if (e.type === "user" && Array.isArray(e.message?.content)) {
      for (const b of e.message.content) {
        if (b.type !== "tool_result") continue;
        const i = byId.get(b.tool_use_id);
        if (i !== undefined && b.is_error === true) record.toolCalls[i]!.isError = true;
      }
    } else if (e.type === "result") {
      finished = true;
      record.turns = Number(e.num_turns ?? 0);
      const u = e.usage ?? {};
      record.inputTokens = Number(u.input_tokens ?? 0) + Number(u.cache_creation_input_tokens ?? 0) + Number(u.cache_read_input_tokens ?? 0);
      record.outputTokens = Number(u.output_tokens ?? 0);
      record.notionalUsd = Number(e.total_cost_usd ?? 0);
      if (e.subtype === "error_max_turns") {
        record.stopReason = "turn-cap";
        record.answer = "";
      } else if (e.is_error === true || e.subtype !== "success") {
        throw new AgentRunError(`The CLI reported ${String(e.subtype ?? "an error")}: ${String(e.result ?? "").slice(0, 200)}`);
      } else {
        record.answer = String(e.result ?? "");
      }
    }
  }
  if (!finished) throw new AgentRunError("The CLI ended without a result.");
  return record;
}

/**
 * Runs one case through the `claude` CLI with only the lab MCP server attached and no built-in
 * tools, so the model can reach nothing else. Uses the CLI's own login, so it is billed to the
 * subscription and not per token. ANTHROPIC_API_KEY is removed from the CLI's environment on
 * purpose: with it set, the CLI would use the API key and its credit balance instead.
 */
export async function runCliAgent(options: {
  prompt: string;
  labUrl: string;
  writes: boolean;
  simulateTimeoutOn?: string;
  model?: string;
}): Promise<CliResult> {
  const dir = mkdtempSync(join(tmpdir(), "mcp-eval-"));
  const env: Record<string, string> = { ...(process.env as Record<string, string>) };
  delete env.ANTHROPIC_API_KEY;
  delete env.ANTHROPIC_AUTH_TOKEN;
  try {
    const config = {
      mcpServers: {
        [SERVER_NAME]: {
          command: resolve(root, "node_modules", ".bin", "tsx"),
          args: [resolve(root, "evals", "proxy.ts")],
          env: { LAB_BASE_URL: options.labUrl, MCP_ALLOW_WRITES: options.writes ? "true" : "false", EVAL_TIMEOUT_TOOL: options.simulateTimeoutOn ?? "" },
        },
      },
    };
    const configFile = join(dir, "mcp.json");
    writeFileSync(configFile, JSON.stringify(config));
    const args = [
      "-p", options.prompt,
      "--output-format", "stream-json", "--verbose",
      "--mcp-config", configFile, "--strict-mcp-config",
      "--tools", "",
      "--allowedTools", `${PREFIX}*`,
      "--max-turns", String(LIMITS.maxTurns),
      "--no-session-persistence",
      "--setting-sources", "",
      "--system-prompt", SYSTEM,
      ...(options.model ? ["--model", options.model] : []),
    ];
    const lines = await new Promise<string[]>((done, fail) => {
      const child = spawn("claude", args, { cwd: dir, env, stdio: ["ignore", "pipe", "pipe"] });
      let out = "";
      let err = "";
      child.stdout.on("data", (d) => (out += String(d)));
      child.stderr.on("data", (d) => (err += String(d)));
      const timer = setTimeout(() => {
        child.kill("SIGKILL");
        fail(new AgentRunError(`The CLI did not finish within ${CASE_TIMEOUT_MS / 1000} s.`));
      }, CASE_TIMEOUT_MS);
      child.on("error", (e) => {
        clearTimeout(timer);
        fail(new AgentRunError(`Could not start the claude CLI: ${e.message}`));
      });
      child.on("close", () => {
        clearTimeout(timer);
        if (!out.trim()) fail(new AgentRunError(`The CLI produced no output. ${err.trim().slice(0, 200)}`));
        else done(out.split("\n").filter(Boolean));
      });
    });
    return parseStream(lines);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
