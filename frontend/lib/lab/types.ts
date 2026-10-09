// Shapes returned by the sandbox backend's /lab/** endpoints (see docs/backend.md).

export interface LabStatus {
  now: string;
  readOnly: boolean;
  timeCompression: {
    reconciliationIntervalMs: number;
    sweepIntervalMs: number;
    reconciliationGraceSeconds: number;
    stalePendingGraceSeconds: number;
    outboxPollMs: number;
    invoiceRepaymentIntervalMs: number;
  };
  next: { reconciliationEstimate: string | null; sweepEstimate: string | null; note: string };
  reset: { lastResetAt: string | null; nextResetAt: string | null; autoResetMinutes: number; cooldownSeconds: number };
  caps: {
    maxVirtualUsers: number;
    maxDurationSeconds: number;
    maxTotalRequests: number;
    runCooldownSeconds: number;
    minAmount: number;
    maxAmount: number;
    maxSlowMs: number;
    requestsPerMinutePerIp: number;
    sseMaxPerIp: number;
    sseIdleTimeoutMinutes: number;
  };
  faultBoundaries: string[];
  faultStatement: string;
}

export interface HealthCheck {
  name: string;
  status: "UP" | "DOWN";
  latencyMs: number;
  checkedAt: string;
  detail: string | null;
}

export interface LabHealth {
  checks: HealthCheck[];
  allUp: boolean;
}

export interface SystemNode {
  id: string;
  count: number;
  lastEventAt: string | null;
}

export interface LabStep {
  order: number;
  key: string;
  title: string;
  at: string | null;
  detail: string;
  source: "database" | "log";
}

export interface SettlementResult {
  settlementId: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amount: number;
  currency: string;
  status: string;
  externalRef: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PlaygroundResponse {
  idempotencyKey: string;
  replay: boolean;
  httpStatus: number;
  result: SettlementResult | null;
  error: string | null;
  fault: string;
  settlementId: string | null;
  stranded: boolean;
  strandedExplanation: string | null;
  externalRecordHeld: boolean;
  orphanedExternalRef: string | null;
  steps: LabStep[];
  requestId: string | null;
}

export interface Inspection {
  settlement: Record<string, unknown>;
  idempotencyKey: Record<string, unknown> | null;
  ledgerEntries: Array<Record<string, unknown>>;
  outboxEvents: Array<Record<string, unknown>>;
  mismatches: Array<Record<string, unknown>>;
  ledgerMismatches: Array<Record<string, unknown>>;
  audit: Array<Record<string, unknown>>;
  logs: LogEvent[];
  traceIds: string[];
  external: { externalRef: string | null; recordHeld: boolean; orphanedExternalRef: string | null };
}

export interface LogEvent {
  seq: number;
  timestamp: string;
  service: string;
  level: string;
  logger: string;
  message: string;
  requestId: string | null;
  settlementId: string | null;
  invoiceId: string | null;
  traceId: string | null;
}

export interface Persona {
  role: string;
  username: string;
  userId: string;
  ownerId: string | null;
  ownedAccounts: string[];
  sandboxPassword: string;
}

export interface LoadSample {
  second: number;
  at: string;
  totalRequests: number;
  requestsPerSecond: number;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
  statusCounts: Record<string, number>;
  outcomeCounts: Record<string, number>;
  finalizeRetries: number;
  unknownFallbacks: number;
  hikariActive: number;
  hikariIdle: number;
  hikariPending: number;
}

export interface Invariant {
  id: string;
  title: string;
  status: "PASS" | "FAIL" | "SKIPPED";
  detail: string;
}

export interface LoadPlan {
  scenario: "FRESH_SETTLEMENTS" | "DUPLICATE_KEY_BURST" | "INVOICE_FINANCING";
  virtualUsers: number;
  durationSeconds: number;
  totalRequests: number;
  amount: number;
  fault: { requestLost: number; responseLost: number; declined: number; slow: number; slowMs: number } | null;
  seed: number | null;
}

export interface LoadRun {
  runId: string;
  status: "RUNNING" | "COMPLETED" | "CANCELLED" | "FAILED";
  plan: LoadPlan;
  startedAt: string;
  finishedAt: string | null;
  samples: LoadSample[];
  summary: {
    totalRequests: number;
    durationSeconds: number;
    averageRequestsPerSecond: number;
    p50Ms: number | null;
    p95Ms: number | null;
    p99Ms: number | null;
    maxMs: number | null;
    statusCounts: Record<string, number>;
    outcomeCounts: Record<string, number>;
    finalizeRetries: number;
    unknownFallbacks: number;
    peakHikariActive: number;
    peakHikariPending: number;
  };
  invariants: Invariant[];
  verdict: "PENDING" | "PASS" | "FAIL" | "NOT_EVALUATED";
  stranded: { unknownOutcomes: number; explanation: string } | null;
  k6Equivalent: string;
  note: string;
}

export interface Scenario {
  id: string;
  group: string;
  title: string;
  visitorSees: string;
  engineDoes: Array<{ class: string; method: string; what: string }>;
  lookFor: string;
  honestLimit: string;
  doc: { path: string; anchor: string; label: string };
  knownGap: number | null;
  action: { route: string; method: string; endpoint: string; body?: unknown };
}

export interface StateMachine {
  states: Array<{ name: string; count: number; noCodePath: boolean; note: string | null; terminal: boolean }>;
  transitions: Array<{ from: string; to: string }>;
}

export interface StateMachines {
  settlement: StateMachine;
  invoice: StateMachine;
  advance: StateMachine;
  readModel: { writeSide: Record<string, number>; readSide: Record<string, number> };
}
