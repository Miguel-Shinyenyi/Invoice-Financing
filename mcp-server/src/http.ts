import type { Config } from "./config.js";
import { ToolError } from "./errors.js";
import { redact, type Logger } from "./log.js";
import { looksLikeLabStatus } from "./safety.js";

export type Role = "READ_ONLY" | "SUPPORT" | "ADMIN";

export interface HttpResult<T = unknown> {
  status: number;
  data: T;
}

interface RequestOptions {
  /** Persona whose token authorises the call. Omit for the Lab's own tokenless views. */
  role?: Role;
  body?: unknown;
  headers?: Record<string, string>;
  query?: Record<string, string | number | undefined>;
}

const MAX_BODY_CHARS = 2_000_000;
const TOKEN_REFRESH_MARGIN_MS = 30_000;

type Sleep = (ms: number) => Promise<void>;
type Clock = () => number;

interface CachedToken {
  token: string;
  expiresAt: number;
}

/**
 * Talks to the Lab backend. Owns the persona tokens (memory only, refreshed before they expire,
 * never logged or returned) and the one-retry policy for the Lab's rate limit.
 */
export class LabClient {
  private readonly tokens = new Map<Role, CachedToken>();
  private readonly inflight = new Map<Role, Promise<string>>();

  constructor(
    private readonly config: Config,
    private readonly log: Logger,
    private readonly deps: { fetch?: typeof fetch; sleep?: Sleep; now?: Clock } = {},
  ) {}

  private get fetchImpl(): typeof fetch {
    return this.deps.fetch ?? fetch;
  }
  private get now(): number {
    return (this.deps.now ?? Date.now)();
  }
  private async sleep(ms: number): Promise<void> {
    await (this.deps.sleep ?? ((n: number) => new Promise<void>((r) => setTimeout(r, n))))(ms);
  }

  get<T = unknown>(path: string, options: RequestOptions = {}): Promise<HttpResult<T>> {
    return this.request<T>("GET", path, options);
  }

  post<T = unknown>(path: string, options: RequestOptions = {}): Promise<HttpResult<T>> {
    return this.request<T>("POST", path, options);
  }

  /** Confirms the target answers as a Lab sandbox. Used once at startup. */
  async fetchLabStatus(): Promise<Record<string, unknown>> {
    const res = await this.rawFetch("GET", "/lab/status", {});
    if (res.status !== 200 || !looksLikeLabStatus(res.data)) {
      throw new ToolError(
        "not_a_lab_sandbox",
        `GET /lab/status returned ${res.status} and not a Lab status snapshot, so this is not a Lab sandbox.`,
      );
    }
    return res.data as Record<string, unknown>;
  }

  private async request<T>(method: string, path: string, options: RequestOptions): Promise<HttpResult<T>> {
    const started = this.now;
    let attempt = 0;
    let reauthed = false;
    for (;;) {
      attempt += 1;
      const res = await this.authorised(method, path, options);
      if (res.status === 401 && options.role && !reauthed) {
        // The token may have been revoked or expired early. Fetch a new one once.
        reauthed = true;
        this.tokens.delete(options.role);
        continue;
      }
      // 429 is the Lab's per-IP bucket and is rejected before any work is done, so even a POST
      // is safe to repeat. 503 is retried for reads only.
      const retryable = res.status === 429 || (res.status === 503 && method === "GET");
      if (retryable && attempt === 1) {
        await this.sleep(this.retryWait(res.retryAfter));
        continue;
      }
      this.log.info("request", { method, path: pathOnly(path), status: res.status, ms: this.now - started });
      return this.interpret<T>(res);
    }
  }

  private retryWait(retryAfterSeconds: number | undefined): number {
    const wanted = (retryAfterSeconds ?? 1) * 1000;
    return Math.min(Math.max(wanted, 250), this.config.retryWaitCapMs);
  }

  private async authorised(method: string, path: string, options: RequestOptions) {
    // Least privilege in code, not only by convention. SUPPORT can write in the engine, so a read
    // persona token must never ride on anything but a GET, and ADMIN must never be used for a read.
    if (options.role && (options.role === "ADMIN") === (method === "GET")) {
      throw new ToolError("upstream_error", "The MCP server refused to send this call with that persona.", undefined, {
        detail: `${options.role} with ${method}`,
      });
    }
    const headers: Record<string, string> = { Accept: "application/json", ...(options.headers ?? {}) };
    if (options.role) headers.Authorization = `Bearer ${await this.tokenFor(options.role)}`;
    return this.rawFetch(method, path, { ...options, headers });
  }

  private async rawFetch(method: string, path: string, options: RequestOptions & { headers?: Record<string, string> }) {
    const url = new URL(path, this.config.baseUrl);
    for (const [k, v] of Object.entries(options.query ?? {})) {
      if (v !== undefined) url.searchParams.set(k, String(v));
    }
    const headers = { ...(options.headers ?? {}) };
    let body: string | undefined;
    if (options.body !== undefined) {
      body = JSON.stringify(options.body);
      headers["Content-Type"] = "application/json";
    }
    let response: Response;
    try {
      response = await this.fetchImpl(url, {
        method,
        headers,
        body,
        redirect: "manual",
        signal: AbortSignal.timeout(this.config.requestTimeoutMs),
      });
    } catch (err) {
      const timedOut = err instanceof Error && (err.name === "TimeoutError" || err.name === "AbortError");
      throw new ToolError(
        "unavailable",
        timedOut
          ? "The Lab did not answer in time. Try again in a few seconds."
          : "Could not reach the Lab sandbox. Check that it is running and LAB_BASE_URL is right.",
      );
    }
    const text = await response.text();
    if (text.length > MAX_BODY_CHARS) {
      throw new ToolError("too_large", "The Lab sent a response too large to process.");
    }
    let data: unknown = null;
    if (text.length > 0) {
      try {
        data = JSON.parse(text);
      } catch {
        data = null;
      }
    }
    const ra = Number(response.headers.get("retry-after"));
    return { status: response.status, data, retryAfter: Number.isFinite(ra) && ra > 0 ? ra : undefined };
  }

  private interpret<T>(res: { status: number; data: unknown; retryAfter: number | undefined }): HttpResult<T> {
    if (res.status >= 200 && res.status < 300) return { status: res.status, data: res.data as T };
    throw mapStatus(res.status, res.data, res.retryAfter);
  }

  private tokenFor(role: Role): Promise<string> {
    const cached = this.tokens.get(role);
    if (cached && cached.expiresAt - this.now > TOKEN_REFRESH_MARGIN_MS) return Promise.resolve(cached.token);
    let pending = this.inflight.get(role);
    if (!pending) {
      pending = this.fetchToken(role).finally(() => this.inflight.delete(role));
      this.inflight.set(role, pending);
    }
    return pending;
  }

  private async fetchToken(role: Role): Promise<string> {
    let res = await this.rawFetch("POST", `/lab/personas/${role}/token`, {});
    if (res.status === 429) {
      await this.sleep(this.retryWait(res.retryAfter));
      res = await this.rawFetch("POST", `/lab/personas/${role}/token`, {});
    }
    const body = res.data as { token?: unknown; expiresInSeconds?: unknown } | null;
    if (res.status !== 200 || typeof body?.token !== "string") {
      throw mapStatus(res.status === 200 ? 502 : res.status, res.data, res.retryAfter, "Could not get a persona token from the Lab.");
    }
    const ttl = typeof body.expiresInSeconds === "number" ? body.expiresInSeconds : 600;
    this.tokens.set(role, { token: body.token, expiresAt: this.now + ttl * 1000 });
    return body.token;
  }
}

function pathOnly(path: string): string {
  const i = path.indexOf("?");
  return i === -1 ? path : path.slice(0, i);
}

/** The engine's error body has a short `message`. Keep it, trimmed and redacted. Never the whole body. */
function upstreamMessage(data: unknown): string | undefined {
  if (typeof data !== "object" || data === null) return undefined;
  const m = (data as Record<string, unknown>).message;
  if (typeof m !== "string") return undefined;
  const clean = redact(m.replace(/\s+/g, " ").trim()).slice(0, 200);
  // Framework messages can name Java classes. Those are not for an agent.
  if (clean === "" || /\b(?:java|org|com|jakarta)\.[a-z]+\./.test(clean) || /Exception/.test(clean)) return undefined;
  return clean;
}

export function mapStatus(status: number, data: unknown, retryAfter?: number, fallback?: string): ToolError {
  const detail = upstreamMessage(data);
  switch (status) {
    case 400:
      return new ToolError("invalid_input", `The engine rejected the request: ${detail ?? "invalid input"}.`);
    case 401:
      return new ToolError("access_denied", "The Lab did not accept the persona token.");
    case 403:
      return new ToolError(
        "access_denied",
        "The persona used for this call may not read this record. The READ_ONLY persona sees only its own accounts and their settlements and invoices. Set MCP_READ_PERSONA=SUPPORT for every row.",
      );
    case 404:
      return new ToolError("not_found", detail ?? "No record with that id.");
    case 409:
      return new ToolError("conflict", `Conflict: ${detail ?? "the request conflicts with an earlier one"}.`);
    case 422:
      return new ToolError("unprocessable", `The engine refused the request: ${detail ?? "unprocessable"}.`);
    case 429:
      return new ToolError(
        "rate_limited",
        `The Lab is rate limiting this client. Wait ${retryAfter ?? 10} seconds, then try again. Do not retry in a loop.`,
        retryAfter ?? 10,
      );
    case 503:
      if (detail && /read-only/i.test(detail)) {
        return new ToolError("read_only", "The Lab is in read-only mode: write actions are switched off right now.");
      }
      return new ToolError("unavailable", "The Lab or one of its dependencies is unavailable. Try again in a few seconds.", retryAfter);
    default:
      return new ToolError("upstream_error", fallback ?? `The Lab answered with an unexpected status ${status}.`, undefined, {
        status,
        detail,
      });
  }
}
