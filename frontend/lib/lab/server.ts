import { cookies } from "next/headers";
import { labBackendUrl } from "../config";
import { LAB_PERSONA_COOKIE } from "./cookies";

/**
 * Server-side fetch against the sandbox backend. Talks to LAB_BACKEND_URL only. It never reads the real login's
 * access_token cookie; it attaches the persona cookie only when asked to (real-endpoint calls).
 */
export async function labFetch(path: string, init: RequestInit & { persona?: boolean } = {}): Promise<Response> {
  const base = labBackendUrl();
  if (!base) throw new Error("LAB_BACKEND_URL is not set");
  const { persona, headers, ...rest } = init;
  const outgoing = new Headers(headers);
  if (persona) {
    const token = (await cookies()).get(LAB_PERSONA_COOKIE)?.value;
    if (token) outgoing.set("Authorization", `Bearer ${token}`);
  }
  return fetch(`${base}${path}`, { ...rest, headers: outgoing, cache: "no-store" });
}

export async function labStatusOrNull(): Promise<unknown | null> {
  if (!labBackendUrl()) return null;
  try {
    const res = await labFetch("/lab/status", { signal: AbortSignal.timeout(2500) });
    return res.ok ? await res.json() : null;
  } catch {
    return null;
  }
}
