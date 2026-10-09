// Friendly names for the fixed seed accounts (lab/seed/lab-seed.sql). Labels only: balances and everything else
// come from the server. An id missing here is shown by its short id.
export const ACCOUNT_LABELS: Record<string, string> = {
  "00000000-0000-0000-0000-000000000001": "Platform",
  "10000000-0000-0000-0000-000000000001": "Pool 1",
  "10000000-0000-0000-0000-000000000002": "Pool 2",
  "10000000-0000-0000-0000-000000000003": "Pool 3",
  "10000000-0000-0000-0000-000000000004": "Pool 4",
  "10000000-0000-0000-0000-000000000005": "Pool 5",
  "10000000-0000-0000-0000-000000000006": "Pool 6",
  "20000000-0000-0000-0000-000000000001": "Duplicate-key source",
  "20000000-0000-0000-0000-000000000002": "Duplicate-key destination",
  "30000000-0000-0000-0000-000000000001": "Load business",
  "40000000-0000-0000-0000-000000000001": "Alice checking (READ_ONLY owns)",
  "40000000-0000-0000-0000-000000000002": "Alice savings (READ_ONLY owns)",
  "50000000-0000-0000-0000-000000000001": "Bob operating",
  "50000000-0000-0000-0000-000000000002": "Bob reserve (balance drifted)",
  "60000000-0000-0000-0000-000000000001": "Northwind Bakery",
  "60000000-0000-0000-0000-000000000002": "Globex Supplies",
  "60000000-0000-0000-0000-000000000003": "Initech Parts",
};

export function accountLabel(id: string): string {
  return ACCOUNT_LABELS[id] ?? `${id.slice(0, 8)}…`;
}

export const POOL_1 = "10000000-0000-0000-0000-000000000001";
export const POOL_2 = "10000000-0000-0000-0000-000000000002";
export const BOB_DRIFTED = "50000000-0000-0000-0000-000000000002";
export const ALICE = "40000000-0000-0000-0000-000000000001";
