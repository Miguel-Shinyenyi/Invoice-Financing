import { describe, expect, it } from "vitest";
import { loadConfig } from "../../src/config.js";
import { StartupRefusal, ToolError } from "../../src/errors.js";
import { LabClient } from "../../src/http.js";
import { createLogger } from "../../src/log.js";
import { assertNotForbidden, looksLikeLabStatus } from "../../src/safety.js";
import { FORBIDDEN_HOSTS } from "../../src/generated/forbiddenHosts.js";
import { FakeLab, LAB_STATUS } from "../helpers/fakeLab.js";

const noDns = async () => {
  throw new Error("dns must not be needed for an IP literal");
};

describe("staging host is refused before any request", () => {
  it("has the staging host in the list generated from the backend config", () => {
    expect(FORBIDDEN_HOSTS).toContain("107.155.122.29");
  });

  it.each([
    "http://107.155.122.29:8080",
    "https://107.155.122.29",
    "http://107.155.122.29./",
    "http://0x6b.155.122.29/",
    `http://${((107 * 256 + 155) * 256 + 122) * 256 + 29}/`,
    "http://0153.0233.0172.035/",
  ])("refuses %s", async (url) => {
    // The URL parser normalises the odd forms to dotted decimal. If it ever stops doing that,
    // this case fails loudly rather than letting an alias through.
    await expect(assertNotForbidden(new URL(url), FORBIDDEN_HOSTS, noDns)).rejects.toBeInstanceOf(StartupRefusal);
  });

  it("refuses a DNS name that resolves to the staging host", async () => {
    await expect(
      assertNotForbidden(new URL("http://lab.example.test:8080"), FORBIDDEN_HOSTS, async () => ["10.0.0.1", "107.155.122.29"]),
    ).rejects.toThrow(/resolves to 107\.155\.122\.29/);
  });

  it("accepts a local sandbox and a name that resolves elsewhere", async () => {
    await expect(assertNotForbidden(new URL("http://127.0.0.1:8080"), FORBIDDEN_HOSTS, noDns)).resolves.toBeUndefined();
    await expect(
      assertNotForbidden(new URL("http://lab.example.test"), FORBIDDEN_HOSTS, async () => ["203.0.113.9"]),
    ).resolves.toBeUndefined();
  });

  it("refuses credentials in the URL and non-http schemes", async () => {
    await expect(assertNotForbidden(new URL("http://user:pw@127.0.0.1:8080"), FORBIDDEN_HOSTS, noDns)).rejects.toThrow(/credentials/);
    await expect(assertNotForbidden(new URL("ftp://127.0.0.1"), FORBIDDEN_HOSTS, noDns)).rejects.toThrow(/http or https/);
  });
});

describe("a target that is not a Lab sandbox is refused", () => {
  const log = createLogger(() => {});

  async function statusFrom(lab: FakeLab) {
    await lab.start();
    try {
      return await new LabClient(loadConfig({ LAB_BASE_URL: lab.baseUrl }), log).fetchLabStatus();
    } finally {
      await lab.stop();
    }
  }

  it("accepts a real Lab status snapshot", async () => {
    const lab = new FakeLab().on("GET /lab/status", { body: LAB_STATUS });
    expect((await statusFrom(lab)).readOnly).toBe(false);
  });

  it("refuses when /lab/status is 404, as on staging where no lab beans exist", async () => {
    const lab = new FakeLab().on("GET /lab/status", { status: 404, body: { status: 404 } });
    await expect(statusFrom(lab)).rejects.toMatchObject({ code: "not_a_lab_sandbox" });
  });

  it("refuses a 200 that is not a Lab status shape", async () => {
    const lab = new FakeLab().on("GET /lab/status", { body: { status: "UP" } });
    await expect(statusFrom(lab)).rejects.toBeInstanceOf(ToolError);
  });

  it("refuses a non-JSON 200", async () => {
    const lab = new FakeLab().on("GET /lab/status", { raw: "<html>hello</html>" });
    await expect(statusFrom(lab)).rejects.toMatchObject({ code: "not_a_lab_sandbox" });
  });

  it("looksLikeLabStatus needs the fault statement, the timers and the caps", () => {
    expect(looksLikeLabStatus(LAB_STATUS)).toBe(true);
    expect(looksLikeLabStatus({ ...LAB_STATUS, faultStatement: undefined })).toBe(false);
    expect(looksLikeLabStatus(null)).toBe(false);
    expect(looksLikeLabStatus("lab")).toBe(false);
  });
});
