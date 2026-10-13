import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import type { Config } from "./config.js";
import { ToolError } from "./errors.js";

export interface Shaped {
  summary: string;
  data: Record<string, unknown>;
}

/** Applies the caller's limit within the configured default and maximum. */
export function resolveLimit(requested: number | undefined, config: Config): number {
  const n = requested ?? config.defaultLimit;
  return Math.max(1, Math.min(config.maxLimit, Math.floor(n)));
}

export interface ListFit<T> {
  items: T[];
  shown: number;
  total: number;
  truncated: boolean;
  /** "showing 20 of 134", or undefined when everything is shown. */
  notice: string | undefined;
}

/**
 * Cuts a list to `limit` rows and then to the response size budget. Truncation is always reported:
 * the caller gets `shown`, `total` and a notice, never a silently shorter list.
 */
export function fitList<T>(all: readonly T[], total: number, limit: number, config: Config, reserve = 600): ListFit<T> {
  let items = all.slice(0, limit);
  const budget = Math.max(500, config.maxResponseChars - reserve);
  while (items.length > 1 && JSON.stringify(items).length > budget) {
    items = items.slice(0, Math.max(1, Math.floor(items.length * 0.75)));
  }
  const truncated = items.length < total;
  return {
    items,
    shown: items.length,
    total,
    truncated,
    notice: truncated ? `showing ${items.length} of ${total}` : undefined,
  };
}

export function ok(shaped: Shaped, config: Config): CallToolResult {
  const text = `${shaped.summary}\n${JSON.stringify(shaped.data)}`;
  if (text.length > config.maxResponseChars) {
    return fail(
      new ToolError("too_large", "The result is larger than the response limit. Ask for fewer rows with a smaller limit."),
    );
  }
  return { content: [{ type: "text", text }], structuredContent: shaped.data };
}

/** A short, safe error for the agent, with what to do next where that is clear. */
export function fail(err: ToolError): CallToolResult {
  const hint = err.retryAfterSeconds !== undefined ? ` (retry after about ${err.retryAfterSeconds}s)` : "";
  const data = { error: err.code, message: err.message, retryAfterSeconds: err.retryAfterSeconds ?? null };
  return {
    isError: true,
    content: [{ type: "text", text: `Error (${err.code}): ${err.message}${hint}` }],
    structuredContent: data,
  };
}

export function toCamel(row: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(row)) {
    out[k.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase())] = v;
  }
  return out;
}

/** Spring serialises a Page either with totals at the top level or under `page`. Accept both. */
export function readPage(body: unknown): { content: unknown[]; total: number; page: number; totalPages: number } {
  const b = (body ?? {}) as Record<string, unknown>;
  const content = Array.isArray(b.content) ? b.content : [];
  const meta = (typeof b.page === "object" && b.page !== null ? b.page : b) as Record<string, unknown>;
  const num = (v: unknown, d: number) => (typeof v === "number" ? v : d);
  return {
    content,
    total: num(meta.totalElements, content.length),
    page: num(meta.number, 0),
    totalPages: num(meta.totalPages, 1),
  };
}
