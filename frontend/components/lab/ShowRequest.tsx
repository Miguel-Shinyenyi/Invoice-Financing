"use client";

import { useSyncExternalStore } from "react";
import { Disclosure, CodeBox } from "@/components/ui";
import type { SentRequest } from "@/lib/lab/client";

function shellQuote(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`;
}

/** The real method, path, headers and body of what the UI just sent, and an equivalent curl a visitor can run. */
export function curlFor(sent: SentRequest, origin: string): string {
  const url = `${origin}${sent.proxyPath}`;
  const lines: string[] = [];
  if (sent.real) {
    lines.push("# Real engine endpoint: pick a persona first (this stores a sandbox JWT in a cookie jar), then call it.");
    lines.push(`curl -sS -c jar.txt -X POST ${origin}/app/api/lab-persona -H 'Content-Type: application/json' -d '{"role":"ADMIN"}'`);
  }
  const parts = [`curl -sS${sent.real ? " -b jar.txt" : ""} -X ${sent.method} ${shellQuote(url)}`];
  for (const [k, v] of Object.entries(sent.headers)) parts.push(`-H ${shellQuote(`${k}: ${v}`)}`);
  if (sent.body !== null) parts.push(`-d ${shellQuote(JSON.stringify(sent.body))}`);
  lines.push(parts.join(" \\\n  "));
  return lines.join("\n");
}

export function ShowRequest({ sent }: { sent: SentRequest | null }) {
  const origin = useSyncExternalStore(
    () => () => {},
    () => window.location.origin,
    () => "",
  );
  if (!sent) return null;
  return (
    <Disclosure summary="Show the request">
      <p className="mb-2 text-xs font-medium">
        {sent.real
          ? "This calls a real engine endpoint, with the chosen persona's JWT in the Authorization header (the proxy adds it from an httpOnly cookie)."
          : "The sandbox backend receives this; the browser reaches it through the proxy path below."}
      </p>
      <CodeBox maxHeight="14rem">
{`${sent.method} ${sent.path}   (sandbox backend)
${sent.method} ${sent.proxyPath}   (what you can call)
${Object.entries(sent.headers).map(([k, v]) => `${k}: ${v}`).join("\n")}${sent.body !== null ? `\n\n${JSON.stringify(sent.body, null, 2)}` : ""}`}
      </CodeBox>
      <p className="mb-1 mt-3 text-xs font-semibold">Equivalent curl</p>
      <CodeBox maxHeight="10rem">{curlFor(sent, origin)}</CodeBox>
    </Disclosure>
  );
}
