import { expect, test } from "@playwright/test";
import { allowed, labGet, labPost, realGet, realPost } from "../lib/lab/proxyPaths";

const UUID = "123e4567-e89b-12d3-a456-426614174000";

test("whitelisted lab paths pass", () => {
  for (const p of ["status", "health", "logs", "logs/stream", "metrics", "kafka", "kafka/topics/settlement.requested/messages", "traces",
    "traces/4bf92f3577b34da6a3ce929d0e0e4736", "data/settlements", `settlements/${UUID}/inspect`, `load/${UUID}/stream`, "reconciliation/stranded"]) {
    expect(allowed(labGet, p), p).toBe(true);
  }
  for (const p of ["settlements", "load/start", `load/${UUID}/cancel`, "reconciliation/run", "ml/fault", `invoices/${UUID}/mark-paid`]) {
    expect(allowed(labPost, p), p).toBe(true);
  }
});

test("anything else is refused, including traversal, encoding and the engine's private paths", () => {
  for (const p of ["actuator/prometheus", "swagger-ui/index.html", "v3/api-docs", "auth/login", "../status", "status/../health", "a//b",
    "data/users;x", "data/%2e%2e", "traces/../../admin", "traces/NOTHEX", "personas/ADMIN/token", "", "status?x=1", "status#x", "load/not-a-uuid"]) {
    expect(allowed(labGet, p), p).toBe(false);
    expect(allowed(labPost, p), p).toBe(false);
  }
});

test("the real-endpoint whitelist is exactly the endpoints the Lab calls", () => {
  expect(allowed(realGet, "settlements")).toBe(true);
  expect(allowed(realGet, `accounts/${UUID}`)).toBe(true);
  expect(allowed(realGet, "reconciliation/ledger-mismatches")).toBe(true);
  expect(allowed(realPost, `reconciliation/ledger-mismatches/${UUID}/resolve`)).toBe(true);
  expect(allowed(realPost, `invoices/${UUID}/finance`)).toBe(true);
  for (const p of ["auth/login", "auth/refresh", "actuator/health", "users", "swagger-ui/index.html", `accounts/${UUID}/../x`]) {
    expect(allowed(realGet, p), p).toBe(false);
    expect(allowed(realPost, p), p).toBe(false);
  }
});
