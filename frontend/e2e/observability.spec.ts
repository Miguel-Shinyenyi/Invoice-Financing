import { expect, test } from "@playwright/test";
import { noHorizontalOverflow, shot } from "./helpers";

// The Part 4B screens: trigger the thing each one shows, confirm it appears in the UI.

test.describe.configure({ mode: "serial" });

async function post(request: import("@playwright/test").APIRequestContext, path: string, data?: unknown, headers: Record<string, string> = {}) {
  const res = await request.post(`/app/api/lab/${path}`, { data, headers });
  expect(res.status(), `${path}: ${await res.text()}`).toBe(200);
  return res.json();
}

test("a trace of a settlement shows SQL and Kafka spans in a waterfall", async ({ page, request }, info) => {
  const created = await post(request, "settlements", {
    sourceAccountId: "10000000-0000-0000-0000-000000000001", destinationAccountId: "10000000-0000-0000-0000-000000000002", amount: 3,
  }, { "X-Request-Id": `e2e-trace-${info.project.name}` });
  await page.goto(`/app/lab/traces?settlementId=${created.settlementId}`);
  // traces reach Jaeger a moment after the request
  await expect(async () => {
    await page.reload();
    await expect(page.locator("tr[data-trace]").first()).toBeVisible({ timeout: 3000 });
  }).toPass({ timeout: 45_000, intervals: [3000] });
  await page.locator("tr[data-trace]").first().getByRole("button", { name: "Open" }).click();
  await expect(page.getByTestId("waterfall")).toBeVisible();
  await expect(page.locator('[data-kind="sql"]').first()).toBeVisible();
  await expect(page.getByTestId("waterfall")).toContainText("settlement-engine-backend");
  await noHorizontalOverflow(page, "traces");
  await shot(page, info, "traces");
});

test("an invoice trace includes the outbound POST /score span into the fraud service", async ({ page, request }, info) => {
  const result = await post(request, "invoices/demo", { scenario: "CLEAN" });
  await page.goto(`/app/lab/traces?invoiceId=${result.invoiceId}`);
  await expect(async () => {
    await page.reload();
    await expect(page.locator("tr[data-trace]").first()).toBeVisible({ timeout: 3000 });
  }).toPass({ timeout: 45_000, intervals: [3000] });
  await page.locator("tr[data-trace]").first().getByRole("button", { name: "Open" }).click();
  await expect(page.getByTestId("waterfall")).toContainText("fraud-detection-ml");
  await expect(page.locator('[data-kind="http-client"]').first()).toBeVisible();
  await shot(page, info, "traces-invoice");
});

test("correlate: one request id returns logs, a trace, audit, outbox and the inspector", async ({ page, request }, info) => {
  const rid = `e2e-correlate-${info.project.name}-${Date.now()}`;
  await post(request, "settlements", {
    sourceAccountId: "10000000-0000-0000-0000-000000000003", destinationAccountId: "10000000-0000-0000-0000-000000000004", amount: 2,
  }, { "X-Request-Id": rid });
  await page.goto(`/app/lab/correlate?requestId=${rid}`);
  await expect(page.getByText(/Log lines, both services \([1-9]/)).toBeVisible();
  await expect(page.getByText(/Outbox events \([1-9]/).first()).toBeVisible();
  await expect(page.getByText(/Inspector · settlement/)).toBeVisible();
  await expect(async () => {
    await page.reload();
    await expect(page.getByTestId("waterfall").first()).toBeVisible({ timeout: 3000 });
  }).toPass({ timeout: 45_000, intervals: [3000] });
  await noHorizontalOverflow(page, "correlate");
  await shot(page, info, "correlate");
});

test("the fraud service's own logs are merged into the log stream", async ({ page, request }, info) => {
  await post(request, "invoices/demo", { scenario: "CLEAN" });
  await page.goto("/app/lab/logs");
  await page.getByLabel("Service").selectOption("ml-service");
  await page.getByRole("button", { name: "Apply" }).click();
  await expect(page.getByTestId("log-tail").locator("[data-level]").first()).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("log-tail")).toContainText("[ml]");
  await shot(page, info, "logs-ml");
});

test("alerts: taking the fraud service down makes MLServiceDown reach firing, then it recovers", async ({ page, request }, info) => {
  test.setTimeout(240_000);
  await page.goto("/app/lab/alerts");
  await expect(page.locator('[data-alert="MLServiceDown"]')).toHaveAttribute("data-state", /inactive|pending|firing/);
  await expect(page.locator('[data-alert="Open reconciliation mismatches"], li:has-text("no rule exists")').first()).toBeVisible();
  const fault = await post(request, "ml/fault", { seconds: 45 });
  expect(fault.seconds).toBe(45);
  await expect(page.locator('[data-alert="MLServiceDown"]')).toHaveAttribute("data-state", "firing", { timeout: 120_000 });
  await shot(page, info, "alerts-firing");
  // the backend fails open while it is down
  const invoice = await post(request, "invoices/demo", { scenario: "CLEAN" });
  expect(invoice.httpStatus).toBe(200);
  await expect(page.locator('[data-alert="MLServiceDown"]')).toHaveAttribute("data-state", "inactive", { timeout: 150_000 });
  await noHorizontalOverflow(page, "alerts");
});

test("the health strip lists every dependency as up", async ({ page }) => {
  await page.goto("/app/lab");
  const strip = page.getByRole("status", { name: "Sandbox health" });
  for (const name of ["backend", "ml-service", "postgres", "kafka", "jaeger", "prometheus", "alertmanager"]) {
    await expect(strip).toContainText(name);
  }
  await expect(strip).not.toContainText("DOWN");
});
