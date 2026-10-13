import { lookup } from "node:dns/promises";
import { isIP } from "node:net";
import { FORBIDDEN_HOSTS } from "./generated/forbiddenHosts.js";
import { StartupRefusal } from "./errors.js";

export type Resolver = (hostname: string) => Promise<string[]>;

const systemResolver: Resolver = async (hostname) => {
  const all = await lookup(hostname, { all: true });
  return all.map((a) => a.address);
};

function stripBrackets(host: string): string {
  return host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : host;
}

/**
 * Refuses a base URL that points at a forbidden host, before any request is made. This mirrors
 * LabSafetyGuard on the backend, which refuses a datasource on the staging host. The list comes
 * from the backend's own config (see scripts/sync-forbidden-hosts.ts), not from this file.
 *
 * The URL parser already turns forms like 0x6b.155.122.29 or 1805550109 into dotted decimal, so
 * the string comparison sees them. A hostname is also resolved, so a DNS name that points at the
 * staging server is refused too.
 */
export async function assertNotForbidden(
  baseUrl: URL,
  forbidden: readonly string[] = FORBIDDEN_HOSTS,
  resolve: Resolver = systemResolver,
): Promise<void> {
  if (baseUrl.protocol !== "http:" && baseUrl.protocol !== "https:") {
    throw new StartupRefusal("LAB_BASE_URL must be an http or https URL.");
  }
  if (baseUrl.username !== "" || baseUrl.password !== "") {
    throw new StartupRefusal("LAB_BASE_URL must not contain credentials.");
  }
  const host = stripBrackets(baseUrl.hostname).replace(/\.$/, "").toLowerCase();
  const blocked = new Set(forbidden.map((h) => h.toLowerCase()));
  if (blocked.has(host)) {
    throw new StartupRefusal(
      `Refusing to start: ${host} is the shared staging server. This MCP server only talks to a Lab sandbox.`,
    );
  }
  if (isIP(host) === 0) {
    let addresses: string[];
    try {
      addresses = await resolve(host);
    } catch {
      // An unresolvable name cannot reach staging. The startup status check reports it.
      return;
    }
    for (const address of addresses) {
      if (blocked.has(address.toLowerCase())) {
        throw new StartupRefusal(
          `Refusing to start: ${host} resolves to ${address}, the shared staging server. This MCP server only talks to a Lab sandbox.`,
        );
      }
    }
  }
}

/** True when a GET /lab/status body looks like the Lab's status snapshot. */
export function looksLikeLabStatus(body: unknown): boolean {
  if (typeof body !== "object" || body === null) return false;
  const b = body as Record<string, unknown>;
  return (
    typeof b.faultStatement === "string" &&
    typeof b.timeCompression === "object" &&
    b.timeCompression !== null &&
    typeof b.caps === "object" &&
    b.caps !== null
  );
}
