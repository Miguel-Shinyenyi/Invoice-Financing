import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    testTimeout: 30_000,
    // Integration tests share one live sandbox, so run files one after another.
    fileParallelism: false,
  },
});
