import { mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import Anthropic from "@anthropic-ai/sdk";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { LIVE_URL, resetLab } from "../support/labData.js";
import { LIMITS, runAgent, type AgentResult } from "./agent.js";
import { CASES, type EvalCase } from "./cases.js";

const root = resolve(import.meta.dirname, "..");
const MAX_CASES = 10;

// The model and prices are inputs, not constants. Change them without editing code.
const MODEL = process.env.EVAL_MODEL ?? "claude-sonnet-5-5";
const PRICE_IN = Number(process.env.EVAL_PRICE_INPUT_PER_MTOK ?? "3");
const PRICE_OUT = Number(process.env.EVAL_PRICE_OUTPUT_PER_MTOK ?? "15");

const args = process.argv.slice(2);
const yes = args.includes("--yes");
const onlyIdx = args.indexOf("--only");
const only = onlyIdx >= 0 ? (args[onlyIdx + 1] ?? "").split(",").filter(Boolean) : null;

function usd(n: number): string {
  return `$${n.toFixed(2)}`;
}

function selected(): EvalCase[] {
  const picked = only ? CASES.filter((c) => only.includes(c.id)) : CASES;
  if (only && picked.length !== only.length) {
    const missing = only.filter((id) => !CASES.some((c) => c.id === id));
    throw new Error(`Unknown case id(s): ${missing.join(", ")}. Known: ${CASES.map((c) => c.id).join(", ")}`);
  }
  return picked.slice(0, MAX_CASES);
}

function estimate(cases: EvalCase[]) {
  const upper = cases.length * (LIMITS.maxInputTokens * PRICE_IN + LIMITS.maxOutputTokens * PRICE_OUT) / 1e6;
  // A typical case here is 3 to 5 calls with a few thousand tokens of tool definitions and results each.
  const typical = cases.length * (20_000 * PRICE_IN + 1_200 * PRICE_OUT) / 1e6;
  return { upper, typical };
}

async function labIsUp(): Promise<boolean> {
  try {
    const res = await fetch(new URL("/lab/status", LIVE_URL), { signal: AbortSignal.timeout(3_000) });
    return res.status === 200;
  } catch {
    return false;
  }
}

async function startServer(writes: boolean): Promise<Client> {
  const transport = new StdioClientTransport({
    command: resolve(root, "node_modules", ".bin", "tsx"),
    args: ["src/index.ts"],
    cwd: root,
    env: { ...(process.env as Record<string, string>), LAB_BASE_URL: LIVE_URL, MCP_ALLOW_WRITES: writes ? "true" : "false" },
    stderr: "ignore", // the server's own log lines are not part of the eval output
  });
  const client = new Client({ name: "evals", version: "0" });
  await client.connect(transport);
  return client;
}

interface CaseResult {
  id: string;
  title: string;
  pass: boolean;
  notes: string[];
  tools: string[];
  turns: number;
  inputTokens: number;
  outputTokens: number;
  stopReason: AgentResult["stopReason"];
  answer: string;
  error?: string;
}

async function main(): Promise<void> {
  if (!process.env.ANTHROPIC_API_KEY) {
    console.error("ANTHROPIC_API_KEY is not set, so the evals did nothing. Set it in your environment (never in a file) and run again.");
    process.exit(2);
  }

  const cases = selected();
  const { upper, typical } = estimate(cases);
  console.log(`Evals: ${cases.length} case(s), model ${MODEL}, up to ${LIMITS.maxTurns} turns each.`);
  console.log(
    `Token budget per case: ${LIMITS.maxInputTokens.toLocaleString()} in, ${LIMITS.maxOutputTokens.toLocaleString()} out. ` +
      `Estimated cost: about ${usd(typical)} typical, at most ${usd(upper)}.`,
  );
  console.log(`Prices used: ${usd(PRICE_IN)} in and ${usd(PRICE_OUT)} out per million tokens (EVAL_PRICE_INPUT_PER_MTOK, EVAL_PRICE_OUTPUT_PER_MTOK). These are assumptions: check current pricing.`);
  if (!yes) {
    console.log("Nothing was run. Add --yes to run these cases and spend the tokens.");
    return;
  }
  if (!(await labIsUp())) {
    console.error(`The Lab sandbox is not reachable at ${LIVE_URL}. Start it with: docker compose -f infra/docker-compose.lab.yml up -d`);
    process.exit(2);
  }

  const anthropic = new Anthropic();
  // Start from the seed so the expected answers hold. A refusal because of the reset cooldown is fine.
  const reset = await resetLab();
  console.log(reset ? "Lab reset to the seed." : "Lab reset refused (cooldown). Using the current state.");
  await new Promise((r) => setTimeout(r, 2_000));

  const readServer = await startServer(false);
  const writeServer = cases.some((c) => c.writes) ? await startServer(true) : null;
  const results: CaseResult[] = [];
  try {
    // Reads first, writes last, so a write case cannot change what a read case expects.
    const ordered = [...cases.filter((c) => !c.writes), ...cases.filter((c) => c.writes)];
    for (const c of ordered) {
      console.log(`\n> ${c.id}: ${c.title}`);
      try {
        const { prompt, state, simulateTimeoutOn } = await c.setup();
        const run = await runAgent(anthropic, MODEL, c.writes ? writeServer! : readServer, prompt, simulateTimeoutOn);
        const verdict = await c.check(run, state);
        const stopped = run.stopReason !== "answered";
        results.push({
          id: c.id,
          title: c.title,
          pass: verdict.pass && !stopped,
          notes: stopped ? [...verdict.notes, `FAIL stopped: ${run.stopReason}`] : verdict.notes,
          tools: run.toolCalls.map((t) => t.name),
          turns: run.turns,
          inputTokens: run.inputTokens,
          outputTokens: run.outputTokens,
          stopReason: run.stopReason,
          answer: run.answer,
        });
      } catch (err) {
        const message = err instanceof Error ? err.message : "unknown error";
        results.push({ id: c.id, title: c.title, pass: false, notes: [`FAIL runner error: ${message}`], tools: [], turns: 0, inputTokens: 0, outputTokens: 0, stopReason: "answered", answer: "", error: message });
      }
      const last = results.at(-1)!;
      console.log(`  ${last.pass ? "PASS" : "FAIL"}  tools: ${last.tools.join(" > ") || "(none)"}  turns: ${last.turns}  tokens: ${last.inputTokens + last.outputTokens}`);
      for (const n of last.notes) console.log(`    ${n}`);
    }
  } finally {
    await readServer.close();
    await writeServer?.close();
  }

  const total = results.reduce((a, r) => a + r.inputTokens + r.outputTokens, 0);
  const cost = results.reduce((a, r) => a + (r.inputTokens * PRICE_IN + r.outputTokens * PRICE_OUT) / 1e6, 0);
  console.log("\nSummary");
  console.log("case                      result  turns  tokens  tools");
  for (const r of results) {
    console.log(`${r.id.padEnd(25)} ${(r.pass ? "PASS" : "FAIL").padEnd(7)} ${String(r.turns).padEnd(6)} ${String(r.inputTokens + r.outputTokens).padEnd(7)} ${r.tools.join(" > ")}`);
  }
  console.log(`\n${results.filter((r) => r.pass).length} of ${results.length} passed. ${total.toLocaleString()} tokens, about ${usd(cost)} at the prices above.`);

  const stamp = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, "").replace(/^(\d{8})/, "$1-");
  const dir = resolve(root, "evals", "results");
  mkdirSync(dir, { recursive: true });
  const file = resolve(dir, `${new Date().toISOString().slice(0, 10)}-${stamp.slice(9)}-${MODEL.replace(/[^a-z0-9.-]/gi, "_")}.json`);
  writeFileSync(file, JSON.stringify({ date: new Date().toISOString(), model: MODEL, labUrl: LIVE_URL, limits: LIMITS, passed: results.filter((r) => r.pass).length, total: results.length, tokens: total, approxCostUsd: Number(cost.toFixed(4)), results }, null, 2) + "\n");
  console.log(`Results written to ${file.replace(root + "/", "")}`);
  process.exit(results.every((r) => r.pass) ? 0 : 1);
}

main().catch((err: unknown) => {
  console.error(err instanceof Error ? err.message : "evals failed");
  process.exit(1);
});
