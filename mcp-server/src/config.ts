export interface Config {
  baseUrl: URL;
  allowWrites: boolean;
  /** Default and maximum rows a list tool returns. */
  defaultLimit: number;
  maxLimit: number;
  /** Largest tool result, in characters of JSON. Anything bigger is cut and says so. */
  maxResponseChars: number;
  requestTimeoutMs: number;
  /** Longest wait before the single retry after a 429 or 503. */
  retryWaitCapMs: number;
}

export const DEFAULT_BASE_URL = "http://127.0.0.1:8080";

function intFrom(raw: string | undefined, fallback: number, min: number, max: number, name: string): number {
  if (raw === undefined || raw.trim() === "") return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min || n > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`);
  }
  return n;
}

export function loadConfig(env: Record<string, string | undefined> = process.env): Config {
  const rawBase = (env.LAB_BASE_URL ?? DEFAULT_BASE_URL).trim();
  let baseUrl: URL;
  try {
    baseUrl = new URL(rawBase);
  } catch {
    throw new Error("LAB_BASE_URL is not a valid URL");
  }
  return {
    baseUrl,
    // Only the exact string "true" turns writes on. "1" or "yes" do not.
    allowWrites: env.MCP_ALLOW_WRITES === "true",
    defaultLimit: 20,
    maxLimit: 100,
    maxResponseChars: intFrom(env.MCP_MAX_RESPONSE_CHARS, 24_000, 2_000, 200_000, "MCP_MAX_RESPONSE_CHARS"),
    requestTimeoutMs: intFrom(env.MCP_REQUEST_TIMEOUT_MS, 10_000, 500, 60_000, "MCP_REQUEST_TIMEOUT_MS"),
    retryWaitCapMs: 3_000,
  };
}
