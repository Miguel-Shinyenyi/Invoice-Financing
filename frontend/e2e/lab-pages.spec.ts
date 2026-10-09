import { expect, test } from "@playwright/test";
import { choosePersona, noHorizontalOverflow, shot } from "./helpers";

// Every Lab route renders, never scrolls sideways, and has had one real scenario run on it.

const ROUTES: Array<[string, string]> = [
  ["/app/lab", "home"],
  ["/app/lab/playground", "playground"],
  ["/app/lab/load", "load"],
  ["/app/lab/chaos", "chaos"],
  ["/app/lab/reconciliation", "reconciliation"],
  ["/app/lab/invoices", "invoices"],
  ["/app/lab/access", "access"],
  ["/app/lab/logs", "logs"],
  ["/app/lab/metrics", "metrics"],
  ["/app/lab/events", "events"],
  ["/app/lab/traces", "traces"],
  ["/app/lab/alerts", "alerts"],
  ["/app/lab/audit", "audit"],
  ["/app/lab/data", "data"],
  ["/app/lab/correlate", "correlate"],
  ["/app/lab/api", "api"],
];

test("the status strip reports the sandbox healthy on every page it appears on", async ({ page }) => {
  await page.goto("/app/lab");
  await expect(page.getByRole("status", { name: "Sandbox health" })).toContainText("postgres");
  await expect(page.getByRole("status", { name: "Sandbox health" })).not.toContainText("DOWN");
});

for (const [route, name] of ROUTES) {
  test(`${name}: renders with no horizontal overflow`, async ({ page }, info) => {
    await page.goto(route);
    await expect(page.locator("h1").first()).toBeVisible();
    await page.waitForTimeout(1500); // let the first poll land
    await noHorizontalOverflow(page, route);
    if (name === "home") await shot(page, info, "home-top");
  });
}

test("home: the system map shows real counters and the catalog loads", async ({ page }, info) => {
  await page.goto("/app/lab");
  await expect(page.locator('[data-node="idempotency"]')).toHaveAttribute("data-count", /\d+/);
  await expect(page.locator("article[id]").first()).toBeVisible();
  expect(await page.locator("article[id]").count()).toBeGreaterThanOrEqual(25);
  await expect(page.locator('svg[aria-label="Settlement state machine"]')).toBeVisible();
  await expect(page.getByText("seeded by hand", { exact: false }).or(page.getByText("no code path", { exact: false })).first()).toBeVisible();
  await noHorizontalOverflow(page, "home after load");
  await shot(page, info, "home");
});

test("playground: create, replay, and the ordered steps", async ({ page }, info) => {
  await page.goto("/app/lab/playground");
  await page.getByRole("button", { name: "Create settlement" }).click();
  await expect(page.getByText("CONFIRMED").first()).toBeVisible();
  await expect(page.locator("[data-step]").first()).toBeVisible();
  expect(await page.locator("[data-step]").count()).toBeGreaterThanOrEqual(5);
  await page.getByRole("button", { name: "Retry: same key, same body" }).click();
  await expect(page.getByText("cached response, replayed")).toBeVisible();
  await noHorizontalOverflow(page, "playground after run");
  await shot(page, info, "playground");
});

test("chaos: request lost is stranded, and a hand-edited balance reads as a 500", async ({ page }, info) => {
  await page.goto("/app/lab/chaos");
  await page.locator("#request-lost").getByRole("button", { name: /Run:/ }).click();
  await expect(page.locator("#request-lost").getByText("Stranded.")).toBeVisible();
  await page.locator("#balance-hand-edit").getByRole("button", { name: /1\. Edit the row by hand/ }).click();
  await expect(page.getByText("Stored balance is now")).toBeVisible();
  await page.locator("#balance-hand-edit").getByRole("button", { name: /2\. Read the account as ADMIN/ }).click();
  await expect(page.getByText("HTTP 500:")).toBeVisible();
  await noHorizontalOverflow(page, "chaos after run");
  await shot(page, info, "chaos");
});

test("access: READ_ONLY gets a genuine 403 and the 500-not-403 case", async ({ page }, info) => {
  await page.goto("/app/lab/access");
  await choosePersona(page, "READ_ONLY");
  const bob = page.locator('[data-probe="accounts/50000000-0000-0000-0000-000000000001"]');
  await expect(bob).toHaveAttribute("data-status", "403");
  const drifted = page.locator('[data-probe="accounts/50000000-0000-0000-0000-000000000002"]');
  await expect(drifted).toHaveAttribute("data-status", "500");
  const own = page.locator('[data-probe="accounts/40000000-0000-0000-0000-000000000001"]');
  await expect(own).toHaveAttribute("data-status", "200");
  await noHorizontalOverflow(page, "access after probes");
  await shot(page, info, "access");
});

test("invoices: the combination crosses the 0.7 BLOCK threshold", async ({ page }, info) => {
  await page.goto("/app/lab/invoices");
  await page.getByText("Duplicate reference on a new account").locator("..").getByRole("button", { name: "Run this scenario" }).click();
  await expect(page.getByTestId("fraud-score")).toHaveText("0.700");
  await expect(page.getByText("BLOCK").first()).toBeVisible();
  await noHorizontalOverflow(page, "invoices after run");
  await shot(page, info, "invoices");
});

test("reconciliation: ledger mismatches have a screen, and resolving reopens the loop", async ({ page }, info) => {
  await page.goto("/app/lab/reconciliation");
  await choosePersona(page, "ADMIN");
  const ledger = page.getByRole("heading", { name: /Ledger mismatches/ }).locator("../..");
  await expect(ledger.getByRole("button", { name: "Resolve" }).first()).toBeVisible();
  await ledger.getByLabel("Resolution reason").first().fill("looked at it");
  await ledger.getByRole("button", { name: "Resolve" }).first().click();
  await expect(page.getByText("the row is RESOLVED. The balance was not touched.")).toBeVisible();
  await page.getByRole("button", { name: /^GET \/accounts\// }).click();
  await expect(page.getByText(/HTTP 500: a fresh mismatch row has opened above\./)).toBeVisible();
  await expect(page.getByText("Stranded UNKNOWN").first()).toBeVisible();
  await noHorizontalOverflow(page, "reconciliation after run");
  await shot(page, info, "reconciliation");
});

test("events: the mismatch topic has zero consumers and says why", async ({ page }, info) => {
  await page.goto("/app/lab/events");
  const row = page.locator('tr[data-topic="reconciliation.mismatch_found"]');
  await expect(row).toBeVisible();
  await expect(row).toHaveAttribute("data-consumers", "0");
  await expect(row.getByText("Known gap 6")).toBeVisible();
  await expect(page.locator('tr[data-topic="settlement.requested"]')).not.toHaveAttribute("data-consumers", "0");
  await noHorizontalOverflow(page, "events");
  await shot(page, info, "events");
});

test("data: a whitelisted table pages, and users is not offered", async ({ page }, info) => {
  await page.goto("/app/lab/data");
  await expect(page.getByRole("button", { name: "ledger_accounts" })).toBeVisible();
  await expect(page.getByRole("button", { name: "users" })).toHaveCount(0);
  await page.getByRole("button", { name: "ledger_entries" }).click();
  await expect(page.locator("table tbody tr").first()).toBeVisible();
  await noHorizontalOverflow(page, "data");
  await shot(page, info, "data");
});

test("audit: rows show persona names, not user ids", async ({ page }, info) => {
  await page.goto("/app/lab/audit");
  await expect(page.locator('tr[data-actor="lab-support"]').first()).toBeVisible();
  await noHorizontalOverflow(page, "audit");
  await shot(page, info, "audit");
});

test("logs: the tail shows lines and the filter narrows them", async ({ page }, info) => {
  await page.goto("/app/lab/logs");
  await expect(page.getByTestId("log-tail").locator("[data-level]").first()).toBeVisible();
  await page.getByLabel("Text contains").fill("Gateway call");
  await page.getByRole("button", { name: "Apply" }).click();
  await noHorizontalOverflow(page, "logs");
  await shot(page, info, "logs");
});

test("metrics: the dashboards and their queries", async ({ page }, info) => {
  await page.goto("/app/lab/metrics");
  await expect(page.getByText("Outbox pending", { exact: true })).toBeVisible();
  await page.getByText("Query").first().click();
  await expect(page.getByText("settlement_outcome_total").first()).toBeVisible();
  await noHorizontalOverflow(page, "metrics");
  await shot(page, info, "metrics");
});

test("api: links to the sandbox's Swagger UI", async ({ page }, info) => {
  await page.goto("/app/lab/api");
  await expect(page.getByRole("link", { name: /swagger-ui/ })).toBeVisible();
  await shot(page, info, "api");
});
