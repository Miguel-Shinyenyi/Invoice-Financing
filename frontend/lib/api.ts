import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ACCESS_TOKEN_COOKIE, decodeAccessToken, type AccessTokenClaims } from "./auth";

// Server-side only: the backend's ClusterIP Service DNS name when deployed in-cluster
// (infra/k8s/14-frontend.yaml sets BACKEND_URL accordingly), localhost for local dev.
const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function currentUser(): Promise<AccessTokenClaims | null> {
  const token = (await cookies()).get(ACCESS_TOKEN_COOKIE)?.value;
  if (!token) return null;
  try {
    return decodeAccessToken(token);
  } catch {
    return null;
  }
}

export async function requireUser(): Promise<AccessTokenClaims> {
  const user = await currentUser();
  if (!user) redirect("/login");
  return user;
}

/**
 * Server-side authenticated fetch against the backend. A 401 means the session is gone (expired
 * access token, no refresh flow wired into this helper for a portfolio-scale app) -- sends the
 * user back to login rather than surfacing a raw API error.
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = (await cookies()).get(ACCESS_TOKEN_COOKIE)?.value;
  const response = await fetch(`${BACKEND_URL}${path}`, {
    ...init,
    headers: {
      ...(init.headers ?? {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      "Content-Type": "application/json",
    },
    cache: "no-store",
  });

  if (response.status === 401) {
    redirect("/login");
  }
  if (!response.ok) {
    const body = await response.text();
    throw new ApiError(response.status, body || response.statusText);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export { BACKEND_URL };
