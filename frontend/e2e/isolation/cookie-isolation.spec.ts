import { expect, test, type APIRequestContext } from "@playwright/test";

// The login cookies may only ever reach BACKEND_URL; the Lab's persona cookie may only ever reach LAB_BACKEND_URL.

const REAL = "http://127.0.0.1:4101";
const LAB = "http://127.0.0.1:4102";

function jwt(role: string, sub: string) {
  const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "none" })}.${b64({ sub, role, exp: Math.floor(Date.now() / 1000) + 900 })}.sig`;
}

const LOGIN_TOKEN = jwt("ADMIN", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa") + "-LOGIN";
const PERSONA_TOKEN = jwt("SUPPORT", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb") + "-PERSONA";

interface Seen { method: string; url: string; headers: Record<string, string>; body: string }

async function log(request: APIRequestContext, base: string): Promise<Seen[]> {
  return (await request.get(`${base}/__log`)).json();
}

test.beforeEach(async ({ request, context }) => {
  await request.get(`${REAL}/__reset`);
  await request.get(`${LAB}/__reset`);
  await context.addCookies([
    { name: "access_token", value: LOGIN_TOKEN, url: "http://127.0.0.1:4100" },
    { name: "lab_persona_token", value: PERSONA_TOKEN, url: "http://127.0.0.1:4100" },
  ]);
});

test("a real-data page sends the login token to the real backend and nothing to the lab backend", async ({ page, request }) => {
  await page.goto("/app/settlements");
  const real = await log(request, REAL);
  expect(real.length).toBeGreaterThan(0);
  expect(real.some((r) => r.headers.authorization === `Bearer ${LOGIN_TOKEN}`)).toBe(true);
  expect(JSON.stringify(real)).not.toContain(PERSONA_TOKEN);
  expect(await log(request, LAB)).toHaveLength(0);
});

test("the lab proxy forwards no cookies and no login token, and never touches the real backend", async ({ page, request }) => {
  await page.goto("/app/lab");
  const res = await page.request.get("/app/api/lab/status");
  expect(res.status()).toBe(200);
  const lab = await log(request, LAB);
  expect(lab.length).toBeGreaterThan(0);
  const everything = JSON.stringify(lab);
  expect(everything).not.toContain(LOGIN_TOKEN);
  expect(everything).not.toContain(PERSONA_TOKEN);
  for (const r of lab) {
    expect(r.headers.cookie).toBeUndefined();
    expect(r.headers.authorization).toBeUndefined();
  }
  expect(await log(request, REAL)).toHaveLength(0);
});

test("the real-endpoint lab proxy sends the persona token, never the login token, and only to the lab backend", async ({ page, request }) => {
  await page.goto("/app/lab");
  const res = await page.request.get("/app/api/lab-real/settlements");
  expect(res.status()).toBe(200);
  const lab = await log(request, LAB);
  const call = lab.find((r) => r.url.startsWith("/settlements"));
  expect(call?.headers.authorization).toBe(`Bearer ${PERSONA_TOKEN}`);
  expect(call?.headers.cookie).toBeUndefined();
  expect(JSON.stringify(lab)).not.toContain(LOGIN_TOKEN);
  expect(await log(request, REAL)).toHaveLength(0);
});

test("choosing a persona asks the lab backend only and sets the lab cookie, not the login cookie", async ({ request }) => {
  const res = await request.post("/app/api/lab-persona", { data: { role: "ADMIN" } });
  expect(res.status()).toBe(200);
  const setCookie = res.headers()["set-cookie"] ?? "";
  expect(setCookie).toContain("lab_persona_token=");
  expect(setCookie).not.toContain("access_token=");
  expect(await log(request, REAL)).toHaveLength(0);
  const lab = await log(request, LAB);
  expect(lab.some((r) => r.url === "/lab/personas/ADMIN/token")).toBe(true);
  expect(JSON.stringify(lab)).not.toContain(LOGIN_TOKEN);
});

test("paths outside the whitelists are refused before any request is made", async ({ request }) => {
  for (const path of ["/app/api/lab/actuator/prometheus", "/app/api/lab/../auth/login", "/app/api/lab/personas/ADMIN/token", "/app/api/lab/data/users"]) {
    const res = await request.get(path);
    expect([404, 400]).toContain(res.status());
  }
  for (const path of ["/app/api/lab-real/auth/login", "/app/api/lab-real/actuator/health", "/app/api/lab-real/users"]) {
    const res = await request.get(path);
    expect(res.status()).toBe(404);
  }
  const lab = await log(request, LAB);
  expect(lab.filter((r) => /actuator|auth\/login|\/users/.test(r.url))).toHaveLength(0);
});
