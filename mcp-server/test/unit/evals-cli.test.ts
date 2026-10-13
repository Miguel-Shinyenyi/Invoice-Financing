import { resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { afterEach, describe, expect, it } from "vitest";
import { TIMEOUT_TEXT } from "../../evals/agent.js";
import { AgentRunError, parseStream } from "../../evals/cliAgent.js";
import { healthyLab, IDS, type FakeLab, type Recorded } from "../helpers/fakeLab.js";

const j = (o: unknown) => JSON.stringify(o);
const init = (status = "connected") =>
  j({ type: "system", subtype: "init", model: "claude-sonnet-5", mcp_servers: [{ name: "lab", status }], tools: ["mcp__lab__get_account", "mcp__lab__create_settlement"] });
const use = (id: string, name: string, input = {}) => j({ type: "assistant", message: { content: [{ type: "tool_use", id, name, input }] } });
const res = (id: string, is_error?: boolean) => j({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: id, content: "x", ...(is_error ? { is_error } : {}) }] } });
const done = (extra = {}) =>
  j({ type: "result", subtype: "success", is_error: false, num_turns: 2, result: "The answer.", total_cost_usd: 0.06, usage: { input_tokens: 4, cache_creation_input_tokens: 100, cache_read_input_tokens: 50, output_tokens: 11 }, ...extra });

describe("parseStream (the claude CLI's stream-json)", () => {
  it("collects tools without the server prefix, the answer, turns, tokens and the model", () => {
    const r = parseStream(["Warning: no stdin data received", init(), use("a", "mcp__lab__get_account", { accountId: "x" }), res("a"), done()]);
    expect(r.toolCalls).toEqual([{ name: "get_account", args: { accountId: "x" }, isError: false }]);
    expect(r.answer).toBe("The answer.");
    expect(r.turns).toBe(2);
    expect(r.inputTokens).toBe(154);
    expect(r.outputTokens).toBe(11);
    expect(r.model).toBe("claude-sonnet-5");
    expect(r.notionalUsd).toBeCloseTo(0.06);
    expect(r.stopReason).toBe("answered");
  });

  it("marks a failed tool result, and a tool the server does not have as unknown", () => {
    const r = parseStream([init(), use("a", "mcp__lab__create_settlement"), res("a", true), use("b", "set_balance"), res("b", true), done()]);
    expect(r.toolCalls.map((c) => [c.name, c.isError])).toEqual([["create_settlement", true], ["set_balance", true]]);
    expect(r.unknownToolCalls).toEqual(["set_balance"]);
  });

  it("reports the turn cap as a stop, not an answer", () => {
    const r = parseStream([init(), done({ subtype: "error_max_turns", is_error: true, result: "ignored" })]);
    expect(r.stopReason).toBe("turn-cap");
    expect(r.answer).toBe("");
  });

  it("throws, instead of scoring, when the server did not connect, the CLI errored, or no result came", () => {
    expect(() => parseStream([init("failed"), done()])).toThrow(AgentRunError);
    expect(() => parseStream([init(), done({ subtype: "error_during_execution", is_error: true, result: "Not logged in" })])).toThrow(/Not logged in/);
    expect(() => parseStream([init()])).toThrow(/without a result/);
  });
});

describe("the eval proxy", () => {
  let lab: FakeLab;
  let client: Client;
  afterEach(async () => {
    await client?.close();
    await lab?.stop();
  });

  it("forwards calls, and loses the first answer from the chosen tool only after it ran for real", async () => {
    lab = healthyLab();
    const seen = new Map<string, unknown>();
    lab.on("POST /settlements", (req: Recorded) => {
      const key = String(req.headers["idempotency-key"]);
      if (!seen.has(key)) seen.set(key, { settlementId: "5e771e00-0000-4000-8000-000000000001", ...(req.body as object), status: "CONFIRMED", externalRef: "M", createdAt: "x", updatedAt: "x" });
      return { status: 201, body: seen.get(key) };
    });
    await lab.start();
    client = new Client({ name: "t", version: "0" });
    await client.connect(
      new StdioClientTransport({
        command: resolve(import.meta.dirname, "../../node_modules/.bin/tsx"),
        args: [resolve(import.meta.dirname, "../../evals/proxy.ts")],
        env: { ...(process.env as Record<string, string>), LAB_BASE_URL: lab.baseUrl, MCP_ALLOW_WRITES: "true", EVAL_TIMEOUT_TOOL: "create_settlement" },
        stderr: "ignore",
      }),
    );
    const names = (await client.listTools()).tools.map((t) => t.name);
    expect(names).toContain("create_settlement");
    expect(names).toContain("get_reconciliation_summary");

    const call = { name: "create_settlement", arguments: { sourceAccountId: "20000000-0000-0000-0000-000000000001", destinationAccountId: "20000000-0000-0000-0000-000000000002", amount: "5", currency: "USD", idempotencyKey: IDS.key } };
    const first = (await client.callTool(call)) as { isError?: boolean; content: { text: string }[] };
    expect(first.isError).toBe(true);
    expect(first.content[0]!.text).toBe(TIMEOUT_TEXT);
    expect(lab.count("POST /settlements")).toBe(1); // it really ran
    const second = (await client.callTool(call)) as { isError?: boolean };
    expect(second.isError).toBeFalsy(); // later calls pass through
    expect(seen.size).toBe(1);
  }, 30_000);
});
