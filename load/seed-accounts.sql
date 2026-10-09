-- Fixed, reproducible account ids for load/settlement-load-test.js -- hardcoded rather than
-- uuidgen'd (unlike the ad-hoc examples in PROJECT.md's "Running the application" section)
-- because the k6 script needs to reference the exact same ids on every run. Idempotent: safe to
-- re-run against an already-seeded database.
--
-- Every account with a nonzero starting balance also gets a matching OPENING ledger entry, since GET /accounts/{id} checks the stored balance against the net of the
-- account's ledger entries and fails loudly on a disagreement -- see docs/reconciliation.md's
-- "Ledger consistency mismatches". Zero-balance accounts need no entry (no entries nets to 0).

-- Pool of accounts the "fresh settlements" scenario picks random pairs from. Large starting
-- balance so many concurrent transfers never hit insufficient-balance under load; the correctness
-- check afterward is that the pool's total balance is unchanged (money moves within the pool, none
-- created or destroyed), not any single account's exact balance.
insert into ledger_accounts (id, owner_id, balance, currency, version, created_at) values
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000f1', 1000000.00, 'USD', 0, now()),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000f2', 1000000.00, 'USD', 0, now()),
    ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-0000000000f3', 1000000.00, 'USD', 0, now()),
    ('10000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-0000000000f4', 1000000.00, 'USD', 0, now()),
    ('10000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-0000000000f5', 1000000.00, 'USD', 0, now()),
    ('10000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-0000000000f6', 1000000.00, 'USD', 0, now())
on conflict (id) do nothing;

-- Dedicated pair, untouched by the pool above, reserved for the "duplicate idempotency key under
-- concurrent load" scenario -- its balance delta after the run is exactly predictable (moves by
-- one settlement's amount if and only if idempotency actually held under real concurrency).
insert into ledger_accounts (id, owner_id, balance, currency, version, created_at) values
    ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000f7', 1000.00, 'USD', 0, now()),
    ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000f8', 0.00, 'USD', 0, now())
on conflict (id) do nothing;

-- Business account for the invoice-financing load scenario.
insert into ledger_accounts (id, owner_id, balance, currency, version, created_at) values
    ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000f9', 0.00, 'USD', 0, now())
on conflict (id) do nothing;

-- Guarded on "no OPENING entry for this account yet" rather than on a fixed entry id, so re-running
-- this against a database seeded before V7 (where V7's backfill already wrote the OPENING entry
-- under a random id) doesn't add a second one.
-- Amount is balance minus the net of any entries already there (not just the balance), so an account
-- that has since received settlements -- e.g. the zero-balance 20..02 after a load run -- isn't
-- given an OPENING entry double-counting money its CREDIT entries already account for.
insert into ledger_entries (id, settlement_id, account_id, entry_type, amount, created_at)
select gen_random_uuid(), null, t.id, 'OPENING', t.opening_amount, t.created_at
from (
    select a.id, a.created_at,
           a.balance - coalesce(sum(case when e.entry_type = 'DEBIT' then -e.amount else e.amount end), 0)
               as opening_amount
    from ledger_accounts a
    left join ledger_entries e on e.account_id = a.id
    where a.id in (
            '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
            '10000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000004',
            '10000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000006',
            '20000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002',
            '30000000-0000-0000-0000-000000000001')
      and not exists (select 1 from ledger_entries o where o.account_id = a.id and o.entry_type = 'OPENING')
    group by a.id, a.created_at, a.balance
) t
where t.opening_amount > 0;
