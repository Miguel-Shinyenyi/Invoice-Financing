import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { GENERATED_FILE, readForbiddenHosts, renderGenerated } from "../../scripts/forbiddenHostsSource.ts";
import { FORBIDDEN_HOSTS } from "../../src/generated/forbiddenHosts.js";

// Same idea as the backend's AlertRulesSyncTest: a copy is allowed only if a test proves it matches.
describe("forbidden hosts match the backend config", () => {
  const fromBackend = readForbiddenHosts();

  it.skipIf(fromBackend === null)("src/generated/forbiddenHosts.ts equals application-demo.yml and the LabProperties default", () => {
    expect([...FORBIDDEN_HOSTS]).toEqual(fromBackend);
    expect(readFileSync(GENERATED_FILE, "utf8")).toBe(renderGenerated(fromBackend ?? []));
  });

  it("is never empty, so the staging check cannot be silently disabled", () => {
    expect(FORBIDDEN_HOSTS.length).toBeGreaterThan(0);
  });
});
