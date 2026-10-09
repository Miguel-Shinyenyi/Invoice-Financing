import fs from "node:fs";
import path from "node:path";
import { expect, test } from "@playwright/test";
import { noHorizontalOverflow, SHOTS, shot } from "./helpers";

// One real load plan at the server's caps (20 users, 30 s, 5000 requests). The measured run is written next to the
// screenshots so its numbers can be quoted exactly, never estimated.

test("a run at the caps finishes and its invariants are reported", async ({ page, request }, info) => {
  test.skip(!info.project.name.startsWith("desktop"), "one run at the caps is enough");
  test.setTimeout(180_000);
  await page.goto("/app/lab/load");
  await expect(page.getByText("caps from the server")).toBeVisible();
  const status = await (await request.get("/app/api/lab/status")).json();
  await page.getByLabel(/^Virtual users/).fill(String(status.caps.maxVirtualUsers));
  await page.getByLabel(/^Duration, seconds/).fill(String(status.caps.maxDurationSeconds));
  await page.getByLabel(/^Total requests/).fill(String(status.caps.maxTotalRequests));
  await page.getByRole("button", { name: "Start run" }).click();
  await expect(page.locator("[data-invariant]").first()).toBeVisible({ timeout: 150_000 });
  await shot(page, info, "load-caps");
  const history = await (await request.get("/app/api/lab/load/history")).json();
  const run = history[0];
  fs.writeFileSync(path.join(SHOTS, "load-caps-run.json"), JSON.stringify(run, null, 2));
  console.log("RUN AT CAPS", JSON.stringify({ plan: run.plan, verdict: run.verdict, summary: run.summary, invariants: run.invariants.map((i: { id: string; status: string; detail: string }) => `${i.id}: ${i.status} (${i.detail})`) }, null, 1));
  expect(run.status).toBe("COMPLETED");
  await noHorizontalOverflow(page, "load after caps run");
});

test("load page: a small run shows live charts and a PASS verdict", async ({ page }, info) => {
  test.setTimeout(120_000);
  await page.goto("/app/lab/load");
  await page.waitForTimeout(11_000); // the 10 s cooldown after any earlier run
  await page.getByLabel(/^Virtual users/).fill("3");
  await page.getByLabel(/^Duration, seconds/).fill("6");
  await page.getByLabel(/^Total requests/).fill("60");
  await page.getByRole("button", { name: "Start run" }).click();
  await expect(page.getByText(/live: (SSE|polling 1s)/)).toBeVisible();
  await expect(page.locator("[data-invariant]").first()).toBeVisible({ timeout: 90_000 });
  await expect(page.locator('[data-invariant][data-status="FAIL"]')).toHaveCount(0);
  await noHorizontalOverflow(page, "load");
  await shot(page, info, "load");
});
