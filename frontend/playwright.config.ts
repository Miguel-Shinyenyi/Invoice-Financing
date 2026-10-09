import { defineConfig } from "@playwright/test";

// The Lab e2e suite drives the real sandbox stack (infra/docker-compose.lab.yml) in a browser. Start it first:
//   docker compose -f infra/docker-compose.lab.yml up --build -d
// then: LAB_URL=http://localhost:3000 npx playwright test
export default defineConfig({
  testDir: "./e2e",
  testIgnore: ["**/isolation/**"],
  timeout: 120_000,
  expect: { timeout: 20_000 },
  workers: 1, // the sandbox allows one load run at a time and rate-limits actions per IP
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.LAB_URL ?? "http://localhost:3000",
    screenshot: "off",
    trace: "off",
  },
  projects: [
    { name: "mobile-375", use: { viewport: { width: 375, height: 812 }, isMobile: false } },
    { name: "desktop-1280", use: { viewport: { width: 1280, height: 900 } } },
  ],
});
