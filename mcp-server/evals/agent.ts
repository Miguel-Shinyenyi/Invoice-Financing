import Anthropic from "@anthropic-ai/sdk";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import type { RunRecord } from "./cases.js";

export const LIMITS = {
  maxTurns: 8,
  /** Cumulative per case. A case that passes either cap stops and fails. */
  maxInputTokens: 60_000,
  maxOutputTokens: 4_000,
  maxTokensPerCall: 1_024,
} as const;

export interface AgentResult extends RunRecord {
  turns: number;
  inputTokens: number;
  outputTokens: number;
  stopReason: "answered" | "turn-cap" | "token-budget";
}

export const TIMEOUT_TEXT = "The request timed out before a response arrived. The outcome is unknown.";

export const SYSTEM =
  "You are an operations assistant for a payments team. You have tools over the settlement engine's Lab sandbox. " +
  "Use the tools to answer, base your answer on what they return, and say plainly when something cannot be done with them.";

/**
 * A tool-use loop over a real MCP client. `simulateTimeoutOn` makes the first call to that tool
 * run for real and then report a timeout to the agent, which is how a lost response looks.
 */
export async function runAgent(
  anthropic: Anthropic,
  model: string,
  mcp: Client,
  prompt: string,
  simulateTimeoutOn?: string,
): Promise<AgentResult> {
  const listed = (await mcp.listTools()).tools;
  const known = new Set(listed.map((t) => t.name));
  const tools = listed.map((t) => ({
    name: t.name,
    description: t.description ?? "",
    input_schema: t.inputSchema as Anthropic.Messages.Tool.InputSchema,
  }));

  const messages: Anthropic.Messages.MessageParam[] = [{ role: "user", content: prompt }];
  const record: AgentResult = { answer: "", toolCalls: [], unknownToolCalls: [], turns: 0, inputTokens: 0, outputTokens: 0, stopReason: "answered" };
  let timeoutPending = simulateTimeoutOn !== undefined;

  for (let turn = 1; turn <= LIMITS.maxTurns; turn += 1) {
    if (record.inputTokens > LIMITS.maxInputTokens || record.outputTokens > LIMITS.maxOutputTokens) {
      record.stopReason = "token-budget";
      return record;
    }
    const res = await anthropic.messages.create({ model, max_tokens: LIMITS.maxTokensPerCall, system: SYSTEM, tools, messages });
    record.turns = turn;
    record.inputTokens += res.usage.input_tokens;
    record.outputTokens += res.usage.output_tokens;
    messages.push({ role: "assistant", content: res.content });

    const text = res.content.flatMap((b) => (b.type === "text" ? [b.text] : [])).join("\n");
    const uses = res.content.flatMap((b) => (b.type === "tool_use" ? [b] : []));
    if (uses.length === 0) {
      record.answer = text;
      return record;
    }

    const results: Anthropic.Messages.ToolResultBlockParam[] = [];
    for (const use of uses) {
      const args = (use.input ?? {}) as Record<string, unknown>;
      if (!known.has(use.name)) {
        record.unknownToolCalls.push(use.name);
        record.toolCalls.push({ name: use.name, args, isError: true });
        results.push({ type: "tool_result", tool_use_id: use.id, is_error: true, content: `There is no tool named ${use.name}.` });
        continue;
      }
      const out = (await mcp.callTool({ name: use.name, arguments: args })) as { isError?: boolean; content: { type: string; text?: string }[] };
      if (timeoutPending && use.name === simulateTimeoutOn) {
        timeoutPending = false;
        record.toolCalls.push({ name: use.name, args, isError: true });
        results.push({
          type: "tool_result",
          tool_use_id: use.id,
          is_error: true,
          content: TIMEOUT_TEXT,
        });
        continue;
      }
      record.toolCalls.push({ name: use.name, args, isError: out.isError === true });
      results.push({
        type: "tool_result",
        tool_use_id: use.id,
        is_error: out.isError === true,
        content: out.content.map((c) => c.text ?? "").join("\n"),
      });
    }
    messages.push({ role: "user", content: results });
  }
  record.stopReason = "turn-cap";
  record.answer = "";
  return record;
}
