import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { FakeLab } from "../helpers/fakeLab.js";

const root = resolve(import.meta.dirname, "..", "..");
const tsx = resolve(root, "node_modules", ".bin", "tsx");

function run(env: Record<string, string>, timeoutMs = 15_000): Promise<{ code: number | null; stderr: string; stdout: string; ms: number }> {
  return new Promise((done) => {
    const started = Date.now();
    const child = spawn(tsx, ["src/index.ts"], { cwd: root, env: { ...process.env, ...env } });
    let stderr = "";
    let stdout = "";
    child.stderr.on("data", (d) => (stderr += String(d)));
    child.stdout.on("data", (d) => (stdout += String(d)));
    const timer = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
    child.on("close", (code) => {
      clearTimeout(timer);
      done({ code, stderr, stdout, ms: Date.now() - started });
    });
  });
}

describe("the server refuses to start", () => {
  it("on the staging host, at once, without trying to connect", async () => {
    const r = await run({ LAB_BASE_URL: "http://107.155.122.29:8080" });
    expect(r.code).toBe(1);
    expect(r.stderr).toMatch(/shared staging server/);
    expect(r.stdout).toBe(""); // stdout is the protocol channel and stays empty
    // A connection attempt to that address would hang until the request timeout. A refusal is quick.
    expect(r.ms).toBeLessThan(8_000);
  });

  it("when the target answers 404 on /lab/status (not a Lab sandbox)", async () => {
    const lab = new FakeLab().on("GET /lab/status", { status: 404, body: { status: 404 } });
    await lab.start();
    try {
      const r = await run({ LAB_BASE_URL: lab.baseUrl });
      expect(r.code).toBe(1);
      expect(r.stderr).toMatch(/not a Lab sandbox/);
      expect(lab.requests.map((q) => `${q.method} ${q.path}`)).toEqual(["GET /lab/status"]);
    } finally {
      await lab.stop();
    }
  });

  it("when nothing is listening, with a short message and no stack", async () => {
    const r = await run({ LAB_BASE_URL: "http://127.0.0.1:1", MCP_REQUEST_TIMEOUT_MS: "1500" });
    expect(r.code).toBe(1);
    expect(r.stderr).toMatch(/Refusing to start/);
    expect(r.stderr).not.toMatch(/\n\s+at /);
  });
});
