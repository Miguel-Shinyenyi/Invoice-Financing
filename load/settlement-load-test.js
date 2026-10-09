// Phase 9 load/chaos test. Run against the local dev stack (docker-compose + ./mvnw spring-boot:run
// -- see PROJECT.md's "Running the application"), never against the shared staging server. Seed
// accounts first: docker exec -i infra-postgres-1 psql -U settlement_engine -d settlement_engine
// < load/seed-accounts.sql
//
// Usage: k6 run load/settlement-load-test.js
// Override target: k6 run -e BASE_URL=http://localhost:8080 load/settlement-load-test.js

import http from "k6/http";
import { check, fail, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

// Fixed ids from load/seed-accounts.sql.
const POOL_ACCOUNTS = [
  "10000000-0000-0000-0000-000000000001",
  "10000000-0000-0000-0000-000000000002",
  "10000000-0000-0000-0000-000000000003",
  "10000000-0000-0000-0000-000000000004",
  "10000000-0000-0000-0000-000000000005",
  "10000000-0000-0000-0000-000000000006",
];
const POOL_STARTING_TOTAL = 6 * 1000000.0;
const DUPLICATE_KEY_SOURCE = "20000000-0000-0000-0000-000000000001";
const DUPLICATE_KEY_DEST = "20000000-0000-0000-0000-000000000002";
const DUPLICATE_KEY_AMOUNT = 50.0;
// One shared idempotency key -- every VU in the duplicate_key_burst scenario reuses this exact
// value, simulating many clients retrying "the same logical request" concurrently.
const SHARED_IDEMPOTENCY_KEY = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
const BUSINESS_ACCOUNT = "30000000-0000-0000-0000-000000000001";

export const options = {
  scenarios: {
    fresh_settlements: {
      executor: "constant-vus",
      vus: 10,
      duration: "20s",
      exec: "freshSettlement",
    },
    duplicate_key_burst: {
      executor: "shared-iterations",
      vus: 20,
      iterations: 20,
      maxDuration: "30s",
      exec: "duplicateKeySettlement",
      startTime: "21s",
    },
    invoice_financing: {
      executor: "shared-iterations",
      vus: 5,
      iterations: 15,
      maxDuration: "30s",
      exec: "financeInvoice",
      startTime: "23s",
    },
  },
};

function uuidv4() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

function authHeaders(token, extra = {}) {
  return { headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", ...extra } };
}

export function setup() {
  const res = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ username: "admin1", password: "password123" }),
    { headers: { "Content-Type": "application/json" } },
  );
  if (res.status !== 200) {
    fail(`login failed: ${res.status} ${res.body} -- did you seed the admin1 user? See PROJECT.md.`);
  }
  return { token: res.json("accessToken") };
}

export function freshSettlement(data) {
  let sourceIdx = Math.floor(Math.random() * POOL_ACCOUNTS.length);
  let destIdx = Math.floor(Math.random() * POOL_ACCOUNTS.length);
  while (destIdx === sourceIdx) {
    destIdx = Math.floor(Math.random() * POOL_ACCOUNTS.length);
  }

  const res = http.post(
    `${BASE_URL}/settlements`,
    JSON.stringify({
      sourceAccountId: POOL_ACCOUNTS[sourceIdx],
      destinationAccountId: POOL_ACCOUNTS[destIdx],
      amount: 1.0,
      currency: "USD",
    }),
    authHeaders(data.token, { "Idempotency-Key": uuidv4() }),
  );

  check(res, { "fresh settlement: 201": (r) => r.status === 201 });
  sleep(0.1);
}

export function duplicateKeySettlement(data) {
  const res = http.post(
    `${BASE_URL}/settlements`,
    JSON.stringify({
      sourceAccountId: DUPLICATE_KEY_SOURCE,
      destinationAccountId: DUPLICATE_KEY_DEST,
      amount: DUPLICATE_KEY_AMOUNT,
      currency: "USD",
    }),
    authHeaders(data.token, { "Idempotency-Key": SHARED_IDEMPOTENCY_KEY }),
  );

  // Both are correct outcomes depending on timing: 201 for the winner or a completed-duplicate
  // replay, 409 for a request that landed while another attempt with this key was still mid-flight.
  // The real proof is the account balance check in teardown, not this per-request status.
  check(res, { "duplicate-key settlement: 201 or 409": (r) => r.status === 201 || r.status === 409 });
}

export function financeInvoice(data) {
  const createRes = http.post(
    `${BASE_URL}/invoices`,
    JSON.stringify({
      businessAccountId: BUSINESS_ACCOUNT,
      customerReference: `load-test-${uuidv4()}`,
      amount: 100.0,
      currency: "USD",
      dueDate: "2027-01-01T00:00:00Z",
    }),
    authHeaders(data.token),
  );
  if (!check(createRes, { "invoice created: 201": (r) => r.status === 201 })) {
    return;
  }

  const invoiceId = createRes.json("id");
  const financeRes = http.post(
    `${BASE_URL}/invoices/${invoiceId}/finance`,
    null,
    authHeaders(data.token, { "Idempotency-Key": uuidv4() }),
  );
  check(financeRes, { "invoice financed: 200": (r) => r.status === 200 });
}

export function teardown(data) {
  let poolTotal = 0;
  for (const accountId of POOL_ACCOUNTS) {
    const res = http.get(`${BASE_URL}/accounts/${accountId}`, authHeaders(data.token));
    poolTotal += res.json("balance");
  }
  check(poolTotal, {
    "pool total balance unchanged (no money created or destroyed)": (total) =>
      Math.abs(total - POOL_STARTING_TOTAL) < 0.0001,
  });

  const sourceRes = http.get(`${BASE_URL}/accounts/${DUPLICATE_KEY_SOURCE}`, authHeaders(data.token));
  const destRes = http.get(`${BASE_URL}/accounts/${DUPLICATE_KEY_DEST}`, authHeaders(data.token));
  check(sourceRes.json("balance"), {
    "duplicate-key source moved by exactly one settlement, not N": (b) => Math.abs(b - (1000.0 - DUPLICATE_KEY_AMOUNT)) < 0.0001,
  });
  check(destRes.json("balance"), {
    "duplicate-key destination moved by exactly one settlement, not N": (b) => Math.abs(b - DUPLICATE_KEY_AMOUNT) < 0.0001,
  });

  console.log(`pool total after run: ${poolTotal} (expected ${POOL_STARTING_TOTAL})`);
  console.log(`duplicate-key source after run: ${sourceRes.json("balance")} (expected ${1000.0 - DUPLICATE_KEY_AMOUNT})`);
  console.log(`duplicate-key destination after run: ${destRes.json("balance")} (expected ${DUPLICATE_KEY_AMOUNT})`);
}
