-- Fixed, reproducible account ids for load/settlement-load-test.js -- hardcoded rather than
-- uuidgen'd (unlike the ad-hoc examples in PROJECT.md's "Running the application" section)
-- because the k6 script needs to reference the exact same ids on every run. Idempotent: safe to
-- re-run against an already-seeded database.

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
