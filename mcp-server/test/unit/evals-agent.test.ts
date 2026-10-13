import type Anthropic from "@anthropic-ai/sdk";
import { afterEach, describe, expect, it } from "vitest";
import { LIMITS, runAgent } from "../../evals/agent.js";
import { mentionsCount } from "../../evals/cases.js";
import { connect, type Harness } from "../helpers/harness.js";
import { healthyLab, IDS, type FakeLab } from "../helpers/fakeLab.js";

let lab: FakeLab;
let h: Harness;

afterEach(async () => {
  await h?.close();
  await lab?.stop();
});

type Block = { type: "text"; text: string } | { type: "tool_use"; id: string; name: string; input: Record<string, unknown> };
type Turn = { content: Block[]; in?: number; out?: number };

/** A model that plays back scripted turns and records what it was sent. */
function scripted(turns: Turn[]) {
  const seen: Anthropic.Messages.MessageParam[][] = [];
  let i = 0;
  const client = {
    messages: {
      create: async (req: { messages: Anthropic.Messages.MessageParam[] }) => {
        seen.push(structuredClone(req.messages));
        const t = turns[Math.min(i, turns.length - 1)]!;
        i += 1;
        return { content: t.content, usage: { input_tokens: t.in ?? 100, output_tokens: t.out ?? 10 }, stop_reason: "end_turn" };
      },
    },
  } as unknown as Anthropic;
  return { client, seen };
}

async function start() {
  lab = healthyLab();
  await lab.start();
  h = await connect(lab.baseUrl, { env: { MCP_ALLOW_WRITES: "true" } });
}

describe("the eval agent loop", () => {
  it("runs tools through the real MCP server and returns the final answer with usage", async () => {
    await start();
    const { client } = scripted([
      { content: [{ type: "tool_use", id: "t1", name: "list_open_reconciliation_mismatches", input: {} }] },
      { content: [{ type: "text", text: "There is 1 open mismatch." }] },
    ]);
    const r = await runAgent(client, "m", h.client, "how many?");
    expect(r.answer).toBe("There is 1 open mismatch.");
    expect(r.toolCalls).toEqual([{ name: "list_open_reconciliation_mismatches", args: {}, isError: false }]);
    expect(r.turns).toBe(2);
    expect(r.inputTokens).toBe(200);
    expect(r.outputTokens).toBe(20);
    expect(r.stopReason).toBe("answered");
  });

  it("answers an invented tool with an error and records it", async () => {
    await start();
    const { client, seen } = scripted([
      { content: [{ type: "tool_use", id: "t1", name: "set_account_balance", input: { accountId: IDS.accountOwned, balance: 9999 } }] },
      { content: [{ type: "text", text: "No tool can do that." }] },
    ]);
    const r = await runAgent(client, "m", h.client, "set it");
    expect(r.unknownToolCalls).toEqual(["set_account_balance"]);
    expect(lab.requests.filter((q) => q.path.includes("balance"))).toEqual([]);
    const lastUser = seen[1]!.at(-1)!.content as { content: string; is_error: boolean }[];
    expect(lastUser[0]!.is_error).toBe(true);
    expect(lastUser[0]!.content).toMatch(/no tool named set_account_balance/);
  });

  it("simulates a lost response: the first create runs for real, the agent is told it timed out", async () => {
    await start();
    let created = 0;
    lab.on("POST /settlements", (req) => ({ status: 201, body: { settlementId: `s-${++created}`, status: "CONFIRMED", ...(req.body as object) } }));
    const args = { sourceAccountId: "20000000-0000-0000-0000-000000000001", destinationAccountId: "20000000-0000-0000-0000-000000000002", amount: "5.00", currency: "USD", idempotencyKey: IDS.key };
    const { client, seen } = scripted([
      { content: [{ type: "tool_use", id: "t1", name: "create_settlement", input: args }] },
      { content: [{ type: "tool_use", id: "t2", name: "create_settlement", input: args }] },
      { content: [{ type: "text", text: "One settlement exists." }] },
    ]);
    const r = await runAgent(client, "m", h.client, "create", "create_settlement");
    expect(lab.count("POST /settlements")).toBe(2); // the timed-out call really reached the Lab
    expect(r.toolCalls.map((c) => c.isError)).toEqual([true, false]);
    const firstResult = seen[1]!.at(-1)!.content as { content: string }[];
    expect(firstResult[0]!.content).toMatch(/timed out/);
    expect(firstResult[0]!.content).not.toMatch(/settlementId/); // the agent never sees the real result
  });

  it("stops at the turn cap and says so", async () => {
    await start();
    const { client } = scripted([{ content: [{ type: "tool_use", id: "t", name: "get_system_status", input: {} }] }]);
    const r = await runAgent(client, "m", h.client, "loop");
    expect(r.turns).toBe(LIMITS.maxTurns);
    expect(r.stopReason).toBe("turn-cap");
    expect(r.answer).toBe("");
  });

  it("stops when the token budget is spent", async () => {
    await start();
    const { client } = scripted([{ content: [{ type: "tool_use", id: "t", name: "get_system_status", input: {} }], in: 40_000, out: 10 }]);
    const r = await runAgent(client, "m", h.client, "expensive");
    expect(r.stopReason).toBe("token-budget");
    expect(r.turns).toBeLessThan(LIMITS.maxTurns);
  });

  it("keeps the documented caps", () => {
    expect(LIMITS.maxTurns).toBe(8);
    expect(LIMITS.maxInputTokens + LIMITS.maxOutputTokens).toBeLessThanOrEqual(64_000);
  });
});

describe("mentionsCount", () => {
  it("accepts digits and words, not substrings", () => {
    expect(mentionsCount("There is 1 open mismatch", 1)).toBe(true);
    expect(mentionsCount("There is one open mismatch", 1)).toBe(true);
    expect(mentionsCount("There are 12 mismatches", 1)).toBe(false);
    expect(mentionsCount("None.", 1)).toBe(false);
  });
});
