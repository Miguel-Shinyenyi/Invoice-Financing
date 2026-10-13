import { describe, expect, it } from "vitest";
import { DEFAULT_BASE_URL, loadConfig, withLabMode } from "../../src/config.js";

describe("config", () => {
  it("defaults to the local sandbox with writes off", () => {
    const c = loadConfig({});
    expect(c.baseUrl.href).toBe(new URL(DEFAULT_BASE_URL).href);
    expect(c.allowWrites).toBe(false);
    expect(c.defaultLimit).toBe(20);
    expect(c.maxLimit).toBe(100);
  });

  it("turns writes on only for the exact string true", () => {
    expect(loadConfig({ MCP_ALLOW_WRITES: "true" }).allowWrites).toBe(true);
    for (const v of ["1", "yes", "TRUE", "True", "", "false"]) {
      expect(loadConfig({ MCP_ALLOW_WRITES: v }).allowWrites).toBe(false);
    }
  });

  it("rejects a bad base URL and out-of-range numbers", () => {
    expect(() => loadConfig({ LAB_BASE_URL: "not a url" })).toThrow(/valid URL/);
    expect(() => loadConfig({ MCP_MAX_RESPONSE_CHARS: "5" })).toThrow(/MCP_MAX_RESPONSE_CHARS/);
    expect(() => loadConfig({ MCP_REQUEST_TIMEOUT_MS: "abc" })).toThrow(/MCP_REQUEST_TIMEOUT_MS/);
  });
});

describe("withLabMode", () => {
  it("drops writes when the Lab reports read-only mode, and says so", () => {
    const r = withLabMode(loadConfig({ MCP_ALLOW_WRITES: "true" }), { readOnly: true });
    expect(r.config.allowWrites).toBe(false);
    expect(r.writesDroppedBecauseLabReadOnly).toBe(true);
  });
  it("keeps writes when the Lab accepts them, and never turns them on", () => {
    expect(withLabMode(loadConfig({ MCP_ALLOW_WRITES: "true" }), { readOnly: false }).config.allowWrites).toBe(true);
    expect(withLabMode(loadConfig({}), { readOnly: false }).config.allowWrites).toBe(false);
  });
});
