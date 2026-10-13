import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

// The MCP resources serve these two docs. Copy them next to the build so the server finds them
// no matter where it runs from.
const here = dirname(fileURLToPath(import.meta.url));
const docs = resolve(here, "..", "..", "docs");
const out = resolve(here, "..", "dist", "docs");
mkdirSync(out, { recursive: true });
for (const name of ["reconciliation.md", "backend.md"]) {
  const src = join(docs, name);
  if (!existsSync(src)) {
    console.log(`copy-docs: ${src} not found, skipping`);
    continue;
  }
  copyFileSync(src, join(out, name));
}
console.log("copy-docs: done");
