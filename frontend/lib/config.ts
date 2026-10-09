// Server-side only. Real data and the sandbox are two different backends, and a cookie for one is
// never sent to the other: the login cookies (access_token, refresh_token) only ever go to
// BACKEND_URL (lib/api.ts), the Lab's persona cookie only ever goes to LAB_BACKEND_URL (lib/lab/server.ts).
export const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

/** The sandbox backend, or null when the Lab is not connected. Read per call, not at import time. */
export function labBackendUrl(): string | null {
  const url = process.env.LAB_BACKEND_URL;
  return url && url.trim() !== "" ? url.trim().replace(/\/$/, "") : null;
}

export const LAB_START_COMMAND = "docker compose -f infra/docker-compose.lab.yml up --build";

// Where the docs the Lab links to live. Overridable at build time.
export const DOCS_BASE =
  process.env.NEXT_PUBLIC_DOCS_BASE ?? "https://github.com/Miguel-Shinyenyi/Invoice-Financing/blob/dev";
