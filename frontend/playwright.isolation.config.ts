import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e/isolation",
  timeout: 60_000,
  workers: 1,
  reporter: [["list"]],
  use: { baseURL: "http://127.0.0.1:4100" },
  webServer: {
    command: "node e2e/isolation/start.mjs",
    url: "http://127.0.0.1:4100/app/about",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
