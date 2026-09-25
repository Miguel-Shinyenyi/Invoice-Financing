-- Opening entries: every account's stored balance must equal the net of its ledger entries
-- (OPENING and CREDIT add, DEBIT subtracts), so LedgerConsistencyService can check one against
-- the other. Accounts are seeded directly with a starting balance and no entries, so without an
-- OPENING row the two would disagree from the very first read.

ALTER TABLE ledger_entries DROP CONSTRAINT ledger_entries_entry_type_check;
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_entry_type_check
    CHECK (entry_type IN ('DEBIT', 'CREDIT', 'OPENING'));

ALTER TABLE ledger_entries ALTER COLUMN settlement_id DROP NOT NULL;
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_settlement_id_by_type_check
    CHECK ((entry_type = 'OPENING' AND settlement_id IS NULL)
        OR (entry_type IN ('DEBIT', 'CREDIT') AND settlement_id IS NOT NULL));

-- Backfill one OPENING entry per existing account, amount = balance - net(existing entries).
-- amount > 0 is still enforced on every entry (V1), so:
--   * zero: no row needed, an account with no entries already nets to 0.
--   * negative: the account was already inconsistent before this migration. Warned and left
--     without an OPENING row rather than coerced -- LedgerConsistencyService will then flag it as
--     a ledger_mismatches row on its first read, which is exactly the manual-review path it needs.
DO $$
DECLARE
    acct RECORD;
BEGIN
    FOR acct IN
        SELECT a.id,
               a.balance,
               a.created_at,
               a.balance - COALESCE(SUM(CASE WHEN e.entry_type = 'DEBIT' THEN -e.amount ELSE e.amount END), 0)
                   AS opening_amount
        FROM ledger_accounts a
        LEFT JOIN ledger_entries e ON e.account_id = a.id
        GROUP BY a.id, a.balance, a.created_at
    LOOP
        IF acct.opening_amount > 0 THEN
            INSERT INTO ledger_entries (id, settlement_id, account_id, entry_type, amount, created_at)
            VALUES (gen_random_uuid(), NULL, acct.id, 'OPENING', acct.opening_amount, acct.created_at);
        ELSIF acct.opening_amount < 0 THEN
            RAISE WARNING 'Ledger account % already inconsistent before V7: balance % is less than net of existing entries by %; no OPENING entry written, will surface as a ledger mismatch',
                acct.id, acct.balance, -acct.opening_amount;
        END IF;
    END LOOP;
END $$;
