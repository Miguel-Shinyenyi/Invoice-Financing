export type ErrorCode =
  | "invalid_input"
  | "not_found"
  | "access_denied"
  | "conflict"
  | "unprocessable"
  | "rate_limited"
  | "unavailable"
  | "read_only"
  | "too_large"
  | "upstream_error"
  | "not_a_lab_sandbox";

/** An error that is safe to show an agent: a short message, never a stack, token or raw body. */
export class ToolError extends Error {
  constructor(
    readonly code: ErrorCode,
    message: string,
    readonly retryAfterSeconds?: number,
    /** For internal decisions only (the HTTP status and a sanitised upstream message). Never shown to the agent. */
    readonly internal: { status?: number; detail?: string } = {},
  ) {
    super(message);
    this.name = "ToolError";
  }
}

/** Raised at startup when the target is not a Lab sandbox. The process exits instead of serving. */
export class StartupRefusal extends Error {
  constructor(message: string) {
    super(message);
    this.name = "StartupRefusal";
  }
}
