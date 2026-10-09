// What a browser may reach through the Lab proxies. Anything not matched here is refused before a request is
// made. Paths are relative to the upstream's /lab/ (labPaths) or to the upstream root (realPaths).
// Never: /actuator, /swagger-ui, /v3/api-docs, /auth.

const UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
const TOKEN = "[A-Za-z0-9_.-]{1,64}";

const re = (s: string) => new RegExp(`^${s}$`);

export const labGet: RegExp[] = [
  re("status"), re("health"), re("scenarios"), re("system"), re("state-machines"), re("personas"),
  re("logs"), re("logs/stream"), re("metrics"), re("events"), re("kafka"), re(`kafka/topics/${TOKEN}/messages`),
  re("traces"), re("traces/[0-9a-f]{16,32}"), re("alerts"), re("audit"), re("requests"), re("correlate"),
  re(`data/${TOKEN}`),
  re(`settlements/${UUID}/inspect`),
  re("reconciliation/runs"), re("reconciliation/mismatches"), re("reconciliation/ledger-mismatches"),
  re("reconciliation/stranded"), re("reconciliation/summary"),
  re("invoices/scenarios"),
  re("load/history"), re(`load/${UUID}`), re(`load/${UUID}/stream`),
];

export const labPost: RegExp[] = [
  re("reset"),
  re("settlements"), re("settlements/retry"), re("settlements/orphan"),
  re(`external/${TOKEN}/forget`), re(`external/${TOKEN}/corrupt`),
  re(`accounts/${UUID}/hand-edit-balance`), re(`accounts/${UUID}/repair-balance`),
  re("reconciliation/run"), re("reconciliation/seen"), re("sweep/run"),
  re("ml/fault"),
  re("invoices/demo"), re(`invoices/${UUID}/mark-paid`), re("invoices/repayment-run"),
  re("load/start"), re(`load/${UUID}/cancel`),
  re("events/out-of-order"),
];

/** Real engine endpoints, called with the persona's token so 200s, 403s and row-level filtering are genuine. */
export const realGet: RegExp[] = [
  re("settlements"), re(`settlements/${UUID}`),
  re(`accounts/${UUID}`), re(`accounts/${UUID}/settlements`),
  re("invoices"), re(`invoices/${UUID}`),
  re("reconciliation/mismatches"), re("reconciliation/ledger-mismatches"),
];

export const realPost: RegExp[] = [
  re("settlements"),
  re("reconciliation/runs"),
  re(`reconciliation/mismatches/${UUID}/resolve`),
  re(`reconciliation/ledger-mismatches/${UUID}/resolve`),
  re("invoices"), re(`invoices/${UUID}/finance`),
];

export function allowed(patterns: RegExp[], path: string): boolean {
  if (path.includes("..") || path.includes("//") || path.includes("%") || path.includes("\\")) return false;
  return patterns.some((p) => p.test(path));
}
