import path from "node:path";
import { expect, type Page, type TestInfo } from "@playwright/test";

export const SHOTS = path.resolve(__dirname, "../../docs/lab-screenshots");

export async function noHorizontalOverflow(page: Page, where: string) {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
  expect(scrollWidth, `${where}: scrollWidth ${scrollWidth} vs clientWidth ${clientWidth}`).toBe(clientWidth);
}

export async function shot(page: Page, info: TestInfo, name: string) {
  const prefix = info.project.name.startsWith("mobile") ? "375" : "1280";
  await page.screenshot({ path: path.join(SHOTS, `${prefix}-${name}.png`), fullPage: true });
}

/** The sandbox rate-limits mutating calls per IP; this keeps the suite well inside the budget by pacing itself. */
export async function settle(page: Page, ms = 300) {
  await page.waitForTimeout(ms);
}

export async function choosePersona(page: Page, role: "ADMIN" | "SUPPORT" | "READ_ONLY") {
  const button = page.getByRole("group", { name: "Acting as" }).getByRole("button", { name: role, exact: true });
  await button.click();
  await expect(button).toHaveAttribute("aria-pressed", "true");
}
