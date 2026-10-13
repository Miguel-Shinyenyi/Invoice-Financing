// stdout carries the MCP protocol, so every log line goes to stderr. Lines are JSON, carry no
// request or response bodies, and pass through redact() so a token can never reach a log.

const JWT = /eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]*/g;
const BEARER = /Bearer\s+[A-Za-z0-9._~+/=-]+/gi;

export function redact(text: string): string {
  return text.replace(JWT, "[redacted-token]").replace(BEARER, "Bearer [redacted]");
}

export type LogSink = (line: string) => void;

export interface Logger {
  info(event: string, fields?: Record<string, string | number | boolean>): void;
  warn(event: string, fields?: Record<string, string | number | boolean>): void;
}

export function createLogger(sink: LogSink = (line) => process.stderr.write(line + "\n")): Logger {
  const write = (level: string, event: string, fields: Record<string, string | number | boolean> = {}) => {
    const safe: Record<string, string | number | boolean> = {};
    for (const [k, v] of Object.entries(fields)) safe[k] = typeof v === "string" ? redact(v) : v;
    sink(redact(JSON.stringify({ level, event, ...safe })));
  };
  return { info: (e, f) => write("info", e, f), warn: (e, f) => write("warn", e, f) };
}
