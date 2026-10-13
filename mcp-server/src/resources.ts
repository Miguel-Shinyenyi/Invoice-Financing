import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { LabClient } from "./http.js";

const here = dirname(fileURLToPath(import.meta.url));

/** dist/docs after a build, or the repo's docs/ folder when running from src. */
export function findDocsDir(): string | null {
  for (const candidate of [join(here, "docs"), join(here, "..", "..", "docs"), join(here, "..", "docs")]) {
    if (existsSync(join(candidate, "reconciliation.md"))) return candidate;
  }
  return null;
}

const MAX_OPENAPI_CHARS = 400_000;

export function registerResources(server: McpServer, client: LabClient, docsDir: string | null = findDocsDir()): void {
  const docs: [string, string, string][] = [
    ["reconciliation-doc", "reconciliation.md", "How the engine guarantees correct money movement: idempotency, the settlement state machine, reconciliation and the known gaps."],
    ["backend-doc", "backend.md", "The backend's endpoints, components and decisions."],
  ];
  for (const [name, file, description] of docs) {
    server.registerResource(
      name,
      `settlement-engine://docs/${file.replace(".md", "")}`,
      { title: file, description, mimeType: "text/markdown" },
      async (uri) => {
        const path = docsDir ? join(docsDir, file) : null;
        const text =
          path && existsSync(path)
            ? readFileSync(path, "utf8")
            : `The ${file} document is not available in this install. Read docs/${file} in the repository.`;
        return { contents: [{ uri: uri.href, mimeType: "text/markdown", text }] };
      },
    );
  }

  server.registerResource(
    "openapi",
    "settlement-engine://openapi",
    {
      title: "OpenAPI description",
      description: "The sandbox backend's OpenAPI 3 document, served live by springdoc. The source for how each endpoint behaves.",
      mimeType: "application/json",
    },
    async (uri) => {
      const res = await client.get("/v3/api-docs");
      let text = JSON.stringify(res.data);
      if (text.length > MAX_OPENAPI_CHARS) {
        text = JSON.stringify({ truncated: true, note: "The OpenAPI document is larger than the size limit. Read it from the sandbox at /v3/api-docs." });
      }
      return { contents: [{ uri: uri.href, mimeType: "application/json", text }] };
    },
  );
}
