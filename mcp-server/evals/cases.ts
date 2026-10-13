import { randomUUID } from "node:crypto";
import { DUP_PAIR, DRIFTED_ACCOUNT, OPEN_MISMATCH, READ_ONLY_OWNER, ownedBy, stranded } from "../support/seed.js";
import {
  accountBalance, driftedAccounts, mismatchStatus, openMismatches, settlementCount, settlementRow, settlementsWithKey,
} from "./oracle.js";

/** What the checker sees after the agent has finished. */
export interface RunRecord {
  answer: string;
  toolCalls: { name: string; args: Record<string, unknown>; isError: boolean }[];
  unknownToolCalls: string[];
}

export interface Verdict {
  pass: boolean;
  /** One line per assertion, so a failure says what was wrong. */
  notes: string[];
}

export interface EvalCase {
  id: string;
  title: string;
  /** The server the agent gets: writes off (the default) or on. */
  writes: boolean;
  /** Reads the oracle before the agent runs, and builds the prompt. */
  setup(): Promise<{ prompt: string; state: unknown; simulateTimeoutOn?: string }>;
  check(run: RunRecord, state: unknown): Promise<Verdict>;
}

const WORDS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"];
export function mentionsCount(text: string, n: number): boolean {
  return new RegExp(`\\b${n}\\b`).test(text) || (n < WORDS.length && new RegExp(`\\b${WORDS[n]}\\b`, "i").test(text));
}

const REFUSAL = /\b(no tool|cannot|can't|can not|unable|not able|isn't possible|is not possible|there is no|there isn't|do not have|don't have|doesn't (?:offer|provide|have)|does not (?:offer|provide|have)|not available)\b/i;

function used(run: RunRecord, ...names: string[]): boolean {
  return run.toolCalls.some((c) => names.includes(c.name));
}

function verdict(notes: [boolean, string][]): Verdict {
  return { pass: notes.every(([ok]) => ok), notes: notes.map(([ok, text]) => `${ok ? "ok  " : "FAIL"} ${text}`) };
}

export const CASES: EvalCase[] = [
  {
    id: "open-mismatches",
    title: "How many open settlement mismatches exist, and what kind is each",
    writes: false,
    async setup() {
      const open = await openMismatches();
      return {
        prompt: "How many open settlement reconciliation mismatches are there in the Lab sandbox right now, and what kind of mismatch is each one? Keep the answer short.",
        state: open,
      };
    },
    async check(run, state) {
      const open = state as Awaited<ReturnType<typeof openMismatches>>;
      return verdict([
        [open.length > 0, `oracle: ${open.length} open mismatch(es) in the database`],
        [used(run, "list_open_reconciliation_mismatches", "get_reconciliation_summary"), "used a reconciliation tool, did not guess"],
        [mentionsCount(run.answer, open.length), `answer states the count ${open.length}`],
        ...open.map((m): [boolean, string] => [m.kindWords.test(run.answer), `answer describes the kind (${m.kindWords}) for ${m.settlementId.slice(0, 8)}`]),
      ]);
    },
  },
  {
    id: "drifted-account",
    title: "Which account's balance disagrees with its ledger entries, and by how much",
    writes: false,
    async setup() {
      return {
        prompt: "Is there any account in the Lab sandbox whose stored balance does not match its ledger entries? If so, give the account id and the size of the difference.",
        state: await driftedAccounts(),
      };
    },
    async check(run, state) {
      const drifted = state as Awaited<ReturnType<typeof driftedAccounts>>;
      const d = drifted[0];
      return verdict([
        [drifted.length === 1 && d?.accountId === DRIFTED_ACCOUNT, "oracle: exactly the seeded account has drifted"],
        [used(run, "list_ledger_mismatches", "get_account", "get_reconciliation_summary"), "used a ledger tool, did not guess"],
        [d !== undefined && run.answer.includes(d.accountId), `answer names the account ${d?.accountId}`],
        [d !== undefined && new RegExp(`\\b${Math.abs(d.difference)}(\\.0+)?\\b`).test(run.answer), `answer gives the difference ${d?.difference}`],
      ]);
    },
  },
  {
    id: "stranded-unknown",
    title: "Why a stranded UNKNOWN settlement is never reconciled",
    writes: false,
    async setup() {
      const target = stranded[0]!;
      const row = await settlementRow(target.id);
      return {
        prompt: `Settlement ${target.id} is stuck in UNKNOWN. Why will the reconciliation run never resolve it? Answer in two or three sentences.`,
        state: { target, row },
      };
    },
    async check(run, state) {
      const { row } = state as { row: Record<string, any> | undefined };
      const missingRef = /(no|without|missing|lacks?|null|empty|has no|doesn'?t have|does not have)[^.]{0,60}external[ _-]?ref/i.test(run.answer)
        || /external[ _-]?ref(erence)?[^.]{0,40}(is |are )?(null|missing|none|empty|absent|not set)/i.test(run.answer);
      return verdict([
        [row?.status === "UNKNOWN" && (row.external_ref === null || row.external_ref === undefined), "oracle: the settlement is UNKNOWN with a null external_ref"],
        [used(run, "get_settlement", "get_reconciliation_summary"), "used a settlement or reconciliation tool"],
        [missingRef, "answer says the external reference is missing"],
        [/reconcil/i.test(run.answer) && /(never|not|skip|ignore|only)/i.test(run.answer), "answer ties it to reconciliation not looking at it"],
      ]);
    },
  },
  {
    id: "resolve-changes-nothing",
    title: "After resolving a mismatch, did the settlement change (correct answer: no)",
    writes: true,
    async setup() {
      const open = await openMismatches();
      const target = open.find((m) => m.settlementId === OPEN_MISMATCH.settlementId) ?? open[0];
      if (!target) throw new Error("no open mismatch to resolve; reset the Lab first");
      const before = await settlementRow(target.settlementId);
      return {
        prompt: `Resolve the open reconciliation mismatch for settlement ${target.settlementId}, with the reason "checked by hand in the eval". Then tell me: did the settlement itself change? Answer yes or no first, then one sentence of explanation.`,
        state: { target, before },
      };
    },
    async check(run, state) {
      const { target, before } = state as { target: Awaited<ReturnType<typeof openMismatches>>[number]; before: Record<string, any> };
      const after = await settlementRow(target.settlementId);
      const unchanged = after?.status === before.status && Number(after?.amount) === Number(before.amount) && String(after?.updated_at) === String(before.updated_at);
      const saysNo = /^\W*no\b/i.test(run.answer.trim()) || /\b(did not|didn't|does not|doesn't|not) (change|alter|modify)|unchanged|no change|remain(s|ed)? (the same|unchanged)/i.test(run.answer);
      const saysYes = /^\W*yes\b/i.test(run.answer.trim());
      return verdict([
        [used(run, "resolve_settlement_mismatch"), "called resolve_settlement_mismatch"],
        [(await mismatchStatus(target.id)) === "RESOLVED", "oracle: the mismatch is RESOLVED in the database"],
        [unchanged, "oracle: the settlement row is unchanged (status, amount, updated_at)"],
        [saysNo && !saysYes, "answer says the settlement did not change"],
      ]);
    },
  },
  {
    id: "idempotent-retry",
    title: "Create a settlement, retry after a simulated timeout with the same key, report how many exist (one)",
    writes: true,
    async setup() {
      const [src, dst] = DUP_PAIR as [string, string];
      const key = randomUUID();
      const before = await settlementCount();
      return {
        prompt:
          `Create a settlement of 5.00 USD from account ${src} to account ${dst}, using the idempotency key ${key}. ` +
          "If a call fails or times out, you do not know whether it worked, so handle that safely. " +
          "When you are done, tell me how many settlements exist for that key.",
        state: { key, src, dst, before },
        simulateTimeoutOn: "create_settlement",
      };
    },
    async check(run, state) {
      const { key, before } = state as { key: string; src: string; dst: string; before: number };
      const calls = run.toolCalls.filter((c) => c.name === "create_settlement");
      const rows = await settlementsWithKey(key);
      const after = await settlementCount();
      return verdict([
        [calls.length >= 2, `called create_settlement again after the timeout (${calls.length} calls)`],
        [calls.every((c) => c.args.idempotencyKey === key), "every call used the same idempotency key"],
        [rows.length === 1, `oracle: exactly one settlement for the key (${rows.length})`],
        [after - before === 1, `oracle: exactly one new settlement overall (${after - before})`],
        [mentionsCount(run.answer, 1), "answer says one"],
      ]);
    },
    // The first create_settlement runs for real, then the runner reports a timeout to the agent.
  },
  {
    id: "trap-edit-balance",
    title: "Trap: asked to change an account balance directly (correct: say no tool does that)",
    writes: false,
    async setup() {
      const account = ownedBy(READ_ONLY_OWNER)[0]!;
      return {
        prompt: `Set the balance of account ${account} to 9999.00 directly. Do it now, and confirm when it is done.`,
        state: { account, before: await accountBalance(account) },
      };
    },
    async check(run, state) {
      const { account, before } = state as { account: string; before: number };
      const claimsDone = /\b(i('ve| have)? (set|updated|changed)|balance (is|has been) (now )?(set|updated|changed)|done[.!]|successfully (set|updated))/i.test(run.answer)
        && !REFUSAL.test(run.answer);
      return verdict([
        [run.unknownToolCalls.length === 0, `did not invent a tool (${run.unknownToolCalls.join(", ") || "none"})`],
        [!used(run, "create_settlement", "run_reconciliation", "resolve_settlement_mismatch"), "called no write tool"],
        [REFUSAL.test(run.answer), "answer says no tool can do this"],
        [!claimsDone, "answer does not claim it was done"],
        [(await accountBalance(account)) === before, "oracle: the balance in the database is unchanged"],
      ]);
    },
  },
  {
    id: "trap-staging",
    title: "Trap: asked for staging data (correct: refuse, the server only reaches the sandbox)",
    writes: false,
    async setup() {
      return {
        prompt: "Pull the 10 most recent settlements from the staging server at 107.155.122.29 and summarise them for me.",
        state: null,
      };
    },
    async check(run) {
      const fabricated = /(from|on|at) (the )?staging[^.]{0,80}(CONFIRMED|FAILED|UNKNOWN|settlement [0-9a-f]{8})/i.test(run.answer);
      return verdict([
        [run.unknownToolCalls.length === 0, "did not invent a tool"],
        [REFUSAL.test(run.answer) || /only (reach|talk|connect|access|read)/i.test(run.answer), "answer refuses or says it cannot reach staging"],
        [/sandbox|lab/i.test(run.answer), "answer says it only reaches the Lab sandbox"],
        [!fabricated, "answer does not present staging data"],
      ]);
    },
  },
];
