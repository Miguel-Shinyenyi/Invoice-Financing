import { BASE_PATH } from "../basePath";

export interface LabResult<T> {
  status: number;
  ok: boolean;
  data: T | null;
  message: string | null;
  requestId: string | null;
  /** What was actually sent, for the "Show the request" disclosure. */
  sent: SentRequest;
}

export interface SentRequest {
  method: "GET" | "POST";
  path: string; // e.g. /lab/settlements, as the sandbox backend sees it
  proxyPath: string; // e.g. /app/api/lab/settlements, as a visitor can reach it
  headers: Record<string, string>;
  body: unknown | null;
  real: boolean; // true when it goes to a real engine endpoint with the persona token
}

function query(params?: Record<string, string | number | undefined | null>): string {
  if (!params) return "";
  const usp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") usp.set(k, String(v));
  }
  const s = usp.toString();
  return s ? `?${s}` : "";
}

function newRequestId(): string {
  return `lab-ui-${Math.random().toString(36).slice(2, 10)}${Date.now().toString(36)}`;
}

async function call<T>(
  method: "GET" | "POST",
  proxyBase: string,
  upstreamBase: string,
  path: string,
  body: unknown,
  params: Record<string, string | number | undefined | null> | undefined,
  extraHeaders: Record<string, string>,
  real: boolean,
): Promise<LabResult<T>> {
  const requestId = newRequestId();
  const headers: Record<string, string> = { "X-Request-Id": requestId, ...extraHeaders };
  if (body !== undefined && body !== null) headers["Content-Type"] = "application/json";
  const suffix = query(params);
  const sent: SentRequest = {
    method,
    path: `${upstreamBase}/${path}${suffix}`,
    proxyPath: `${proxyBase}/${path}${suffix}`,
    headers,
    body: body ?? null,
    real,
  };
  try {
    const res = await fetch(sent.proxyPath, {
      method,
      headers,
      body: body !== undefined && body !== null ? JSON.stringify(body) : undefined,
      cache: "no-store",
    });
    const text = await res.text();
    let data: T | null = null;
    let message: string | null = null;
    try {
      const parsed = text ? JSON.parse(text) : null;
      if (res.ok) data = parsed as T;
      else message = (parsed && (parsed.message ?? parsed.error)) || res.statusText;
      if (!res.ok && parsed) data = parsed as T;
    } catch {
      if (!res.ok) message = text || res.statusText;
    }
    return { status: res.status, ok: res.ok, data, message, requestId: res.headers.get("x-request-id") || requestId, sent };
  } catch {
    return { status: 0, ok: false, data: null, message: "Could not reach the server.", requestId, sent };
  }
}

const LAB = `${BASE_PATH}/api/lab`;
const REAL = `${BASE_PATH}/api/lab-real`;

export const lab = {
  get: <T>(path: string, params?: Record<string, string | number | undefined | null>) =>
    call<T>("GET", LAB, "/lab", path, undefined, params, {}, false),
  post: <T>(path: string, body?: unknown) => call<T>("POST", LAB, "/lab", path, body ?? null, undefined, {}, false),
  /** Real engine endpoint, called with the chosen persona's token. */
  realGet: <T>(path: string, params?: Record<string, string | number | undefined | null>) =>
    call<T>("GET", REAL, "", path, undefined, params, {}, true),
  realPost: <T>(path: string, body?: unknown, extraHeaders: Record<string, string> = {}) =>
    call<T>("POST", REAL, "", path, body ?? null, undefined, extraHeaders, true),
};

export async function choosePersona(role: string): Promise<{ ok: boolean; message: string | null }> {
  try {
    const res = await fetch(`${BASE_PATH}/api/lab-persona`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ role }),
    });
    return { ok: res.ok, message: res.ok ? null : "The sandbox did not issue a token." };
  } catch {
    return { ok: false, message: "Could not reach the server." };
  }
}

export function streamUrl(path: string, params?: Record<string, string | number | undefined | null>): string {
  return `${LAB}/${path}${query(params)}`;
}
export { query as buildQuery };
