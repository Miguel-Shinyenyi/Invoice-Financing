import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";

export interface Recorded {
  method: string;
  path: string;
  query: URLSearchParams;
  headers: IncomingMessage["headers"];
  body: unknown;
}

export interface FakeReply {
  status?: number;
  body?: unknown;
  headers?: Record<string, string>;
  /** Send raw text instead of JSON. */
  raw?: string;
}

export type Handler = (req: Recorded) => FakeReply | Promise<FakeReply>;

/**
 * A stand-in for the Lab backend. Routes are exact "METHOD /path" keys. Every request is recorded,
 * so tests can assert what was sent (and what was not).
 */
export class FakeLab {
  readonly requests: Recorded[] = [];
  private readonly routes = new Map<string, Handler>();
  private server: Server | undefined;
  baseUrl = "";
  tokenCounter = 0;
  /** Lifetime, in seconds, of the tokens the fake issues. */
  tokenTtl = 900;

  on(key: string, handler: Handler | FakeReply): this {
    this.routes.set(key, typeof handler === "function" ? handler : () => handler);
    return this;
  }

  async start(): Promise<void> {
    this.server = createServer((req, res) => void this.handle(req, res));
    await new Promise<void>((resolve) => this.server!.listen(0, "127.0.0.1", resolve));
    this.baseUrl = `http://127.0.0.1:${(this.server.address() as AddressInfo).port}`;
  }

  async stop(): Promise<void> {
    await new Promise<void>((resolve) => (this.server ? this.server.close(() => resolve()) : resolve()));
  }

  count(key: string): number {
    const [method, path] = key.split(" ");
    return this.requests.filter((r) => r.method === method && r.path === path).length;
  }

  private async handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? "/", "http://fake");
    const chunks: Buffer[] = [];
    for await (const c of req) chunks.push(c as Buffer);
    const text = Buffer.concat(chunks).toString("utf8");
    const rec: Recorded = {
      method: req.method ?? "GET",
      path: url.pathname,
      query: url.searchParams,
      headers: req.headers,
      body: text ? (JSON.parse(text) as unknown) : undefined,
    };
    this.requests.push(rec);
    const handler = this.routes.get(`${rec.method} ${rec.path}`);
    const reply: FakeReply = handler ? await handler(rec) : { status: 404, body: { status: 404, message: "No route" } };
    res.statusCode = reply.status ?? 200;
    for (const [k, v] of Object.entries(reply.headers ?? {})) res.setHeader(k, v);
    if (reply.raw !== undefined) {
      res.end(reply.raw);
    } else {
      res.setHeader("Content-Type", "application/json");
      res.end(reply.body === undefined ? "" : JSON.stringify(reply.body));
    }
  }
}

// ---- Fixtures copied from real responses of the Lab (shapes, not invented ones). -----------------

export const IDS = {
  settlementConfirmed: "d6c42e25-4c0e-5d02-fdd0-e1241f82d2df",
  settlementStranded: "d8524726-d579-29a4-1b4a-525db41a01d3",
  accountOwned: "40000000-0000-0000-0000-000000000001",
  accountDrifted: "50000000-0000-0000-0000-000000000002",
  mismatch: "71000000-0000-0000-0000-000000000011",
  mismatchSettlement: "a98d54c3-6870-665f-d393-179b83e03ca4",
  ledgerMismatch: "72000000-0000-0000-0000-000000000001",
  invoice: "80000000-0000-0000-0000-000000000002",
  key: "3f5b8c2e-6b0a-4e9b-9d1a-8f2f7a6b1c33",
};

export const LAB_STATUS = {
  now: "2026-10-13T09:35:47Z",
  readOnly: false,
  timeCompression: { reconciliationIntervalMs: 5000, sweepIntervalMs: 5000, reconciliationGraceSeconds: 20, stalePendingGraceSeconds: 20, outboxPollMs: 200, invoiceRepaymentIntervalMs: 5000 },
  next: { reconciliationEstimate: "2026-10-13T09:35:48Z", sweepEstimate: "2026-10-13T09:35:48Z", note: "Estimate" },
  reset: { lastResetAt: "2026-10-13T09:35:42Z", nextResetAt: "2026-10-13T10:05:42Z", autoResetMinutes: 30, cooldownSeconds: 60 },
  caps: { maxVirtualUsers: 20, maxDurationSeconds: 30, requestsPerMinutePerIp: 30 },
  faultBoundaries: ["the gateway call"],
  faultStatement: "Faults are injected only at the boundaries above.",
};

export const SANDBOX_PASSWORD = "lab-admin-sandbox";

export const PERSONAS = [
  { role: "ADMIN", username: "lab-admin", userId: "00000000-0000-0000-0000-0000000000c1", ownerId: null, ownedAccounts: [], sandboxPassword: SANDBOX_PASSWORD },
  { role: "READ_ONLY", username: "lab-readonly", userId: "00000000-0000-0000-0000-0000000000c3", ownerId: "00000000-0000-0000-0000-0000000000b1", ownedAccounts: [IDS.accountOwned], sandboxPassword: "lab-readonly-sandbox" },
];

export const STATE_MACHINES = {
  settlement: {
    states: [
      { name: "PENDING", count: 1, noCodePath: false, note: null, terminal: false },
      { name: "CONFIRMED", count: 13, noCodePath: false, note: null, terminal: false },
      { name: "FAILED", count: 2, noCodePath: false, note: null, terminal: true },
      { name: "UNKNOWN", count: 2, noCodePath: false, note: null, terminal: false },
      { name: "REVERSED", count: 1, noCodePath: true, note: "Reachable from CONFIRMED in the state machine, but no code in src/main ever transitions to it. Known gap 3.", terminal: true },
    ],
    transitions: [
      { from: "PENDING", to: "CONFIRMED" }, { from: "PENDING", to: "FAILED" }, { from: "PENDING", to: "UNKNOWN" },
      { from: "CONFIRMED", to: "REVERSED" }, { from: "UNKNOWN", to: "CONFIRMED" }, { from: "UNKNOWN", to: "FAILED" },
    ],
  },
  invoice: { states: [{ name: "ISSUED", count: 1, noCodePath: false, note: null, terminal: false }], transitions: [{ from: "ISSUED", to: "FINANCED" }] },
  advance: { states: [{ name: "DEFAULTED", count: 0, noCodePath: true, note: "Nothing sets it.", terminal: true }], transitions: [] },
};

export const MISMATCH_ROW = {
  id: IDS.mismatch, runId: "70000000-0000-0000-0000-000000000005", settlementId: IDS.mismatchSettlement,
  internalState: "CONFIRMED", externalState: "CONFIRMED",
  details: "External record amount/currency (99.0000 USD) does not match internal (90.0000 USD)",
  resolutionStatus: "OPEN", resolvedAt: null, createdAt: "2026-10-13T09:30:42Z",
};

export const LEDGER_MISMATCH_ROW = {
  id: IDS.ledgerMismatch, accountId: IDS.accountDrifted, storedBalance: 1525.0, computedBalance: 1500.0,
  details: "Stored balance 1525.0000 does not match net of ledger entries 1500.0000", resolutionStatus: "OPEN", resolvedAt: null, createdAt: "2026-10-13T09:32:42Z",
};

export const STRANDED_ROW = {
  settlementId: IDS.settlementStranded, sourceAccountId: "10000000-0000-0000-0000-000000000002", destinationAccountId: "10000000-0000-0000-0000-000000000004",
  amount: 18.0, currency: "USD", status: "UNKNOWN", externalRef: null, createdAt: "2026-10-13T07:45:42Z", updatedAt: "2026-10-13T07:45:42Z",
};

export function inspectBody(status: "CONFIRMED" | "UNKNOWN") {
  return {
    settlement: {
      id: status === "UNKNOWN" ? IDS.settlementStranded : IDS.settlementConfirmed,
      idempotency_key: IDS.key,
      source_account_id: "60000000-0000-0000-0000-000000000002",
      destination_account_id: "00000000-0000-0000-0000-000000000001",
      amount: 18.0, currency: "USD", status,
      external_ref: status === "UNKNOWN" ? null : "MOCK-SEED-18",
      created_at: "2026-10-13T08:55:42Z", updated_at: "2026-10-13T08:55:42Z",
    },
    idempotencyKey: { key: IDS.key, request_hash: "deadbeef", response_snapshot: "{}", status: "COMPLETED" },
    ledgerEntries: status === "UNKNOWN" ? [] : [
      { id: "e1", settlement_id: IDS.settlementConfirmed, account_id: "00000000-0000-0000-0000-000000000001", entry_type: "CREDIT", amount: 18.0, created_at: "2026-10-13T08:55:42Z" },
      { id: "e2", settlement_id: IDS.settlementConfirmed, account_id: "60000000-0000-0000-0000-000000000002", entry_type: "DEBIT", amount: 18.0, created_at: "2026-10-13T08:55:42Z" },
    ],
    outboxEvents: [{ topic: "settlement.requested", payload: "{}" }, { topic: status === "UNKNOWN" ? "settlement.unknown" : "settlement.confirmed", payload: "{}" }],
    mismatches: [], ledgerMismatches: [], audit: [], logs: [], traceIds: [],
    external: { externalRef: status === "UNKNOWN" ? null : "MOCK-SEED-18", recordHeld: status !== "UNKNOWN", orphanedExternalRef: null },
  };
}

/** A fake that behaves like a healthy Lab. Individual tests override routes. */
export function healthyLab(): FakeLab {
  const lab = new FakeLab();
  lab.on("GET /lab/status", { body: LAB_STATUS });
  lab.on("GET /lab/health", { body: { checks: ["backend", "ml-service", "postgres", "kafka", "jaeger", "prometheus", "alertmanager"].map((name) => ({ name, status: "UP", latencyMs: 5, checkedAt: "x", detail: null })) } });
  lab.on("GET /lab/system", { body: { at: "x", nodes: [{ id: "client", count: 19, lastEventAt: "x" }, { id: "kafka", count: 0, lastEventAt: null }] } });
  lab.on("GET /lab/personas", { body: PERSONAS });
  lab.on("GET /lab/state-machines", { body: STATE_MACHINES });
  lab.on("POST /lab/personas/READ_ONLY/token", (r) => ({ body: issue(lab, "READ_ONLY", r) }));
  lab.on("POST /lab/personas/SUPPORT/token", (r) => ({ body: issue(lab, "SUPPORT", r) }));
  lab.on("POST /lab/personas/ADMIN/token", (r) => ({ body: issue(lab, "ADMIN", r) }));
  lab.on("GET /lab/reconciliation/mismatches", { body: [MISMATCH_ROW] });
  lab.on("GET /lab/reconciliation/ledger-mismatches", { body: [LEDGER_MISMATCH_ROW] });
  lab.on("GET /lab/reconciliation/summary", { body: { openMismatches: 1, openLedgerMismatches: 1, unseenMismatches: 0, strandedUnknown: 1, note: "Nothing alerts a human. Known gap 6." } });
  lab.on("GET /lab/reconciliation/stranded", { body: { settlements: [STRANDED_ROW], explanation: "UNKNOWN with no external reference. Known gap 1." } });
  lab.on("GET /lab/reconciliation/runs", { body: [{ id: "r1", startedAt: "x", finishedAt: "y", recordsChecked: 13, mismatchesFound: 1, status: "COMPLETED" }] });
  lab.on(`GET /lab/settlements/${IDS.settlementStranded}/inspect`, { body: inspectBody("UNKNOWN") });
  lab.on(`GET /lab/settlements/${IDS.settlementConfirmed}/inspect`, { body: inspectBody("CONFIRMED") });
  return lab;
}

function issue(lab: FakeLab, role: string, _r: Recorded) {
  lab.tokenCounter += 1;
  // A JWT-shaped string, so the log redaction and the leak tests have something realistic to find.
  const token = `eyJhbGciOiJIUzM4NCJ9.eyJyb2xlIjoi${role}Iiwibm9uY2UiOi${lab.tokenCounter}In0.sig${role}${lab.tokenCounter}`;
  return { persona: { role }, token, expiresInSeconds: lab.tokenTtl };
}

export function roleOf(authHeader: string | string[] | undefined): string {
  const h = String(authHeader ?? "");
  return h.includes("ADMIN") ? "ADMIN" : h.includes("SUPPORT") ? "SUPPORT" : h.includes("READ_ONLY") ? "READ_ONLY" : "none";
}
