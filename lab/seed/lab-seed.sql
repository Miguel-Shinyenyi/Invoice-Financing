-- Deterministic seed for the public Lab sandbox. Loaded on startup and by every reset, after the
-- data tables are truncated. Sandbox only: the credentials below are fixed, public, and exist in
-- no other environment. Never run this against the staging database (LabSafetyGuard refuses a
-- database whose name does not end in _lab).
--
-- Style follows load/seed-accounts.sql: fixed ids, idempotent inserts (on conflict do nothing).
-- Every account with a nonzero opening balance gets a matching OPENING ledger entry, and every
-- stored balance is then set to the net of its entries -- except one account, deliberately drifted
-- by +25.00 so the "open ledger mismatch" story has a real cause. A seed that breaks that rule
-- is a bug (LabSeedIntegrityTest).
--
-- Statements are separated by semicolons at line end. Do not put a semicolon inside a string.

-- ---------------------------------------------------------------- users (sandbox personas)
-- Passwords: lab-admin-sandbox, lab-support-sandbox, lab-readonly-sandbox. SANDBOX ONLY.
insert into users (id, username, password_hash, role, owner_id, created_at) values
    ('00000000-0000-0000-0000-0000000000c1', 'lab-admin',
     '$2a$10$OeCOPkAAYKO0OzGodlHFK.0KYKCWUBlRjXe83EUsaJW6ZVOyIHAim', 'ADMIN', null, now() - interval '30 days'),
    ('00000000-0000-0000-0000-0000000000c2', 'lab-support',
     '$2a$10$VJxYoVq8PlJeiVtHz5llPOjk1ftgqamU2URes1B1zEuXE8NaXgBjK', 'SUPPORT', null, now() - interval '30 days'),
    ('00000000-0000-0000-0000-0000000000c3', 'lab-readonly',
     '$2a$10$PF4HkW8eCuPHQANYeAupP.FrycvfCOQWCg7MMNbB7iFMDQGMU62aO', 'READ_ONLY',
     '00000000-0000-0000-0000-0000000000b1', now() - interval '30 days')
on conflict (id) do nothing;

-- ---------------------------------------------------------------- accounts
-- Opening balances. The final balance column is recomputed from entries at the end of this file.
create temp table seed_accounts (id uuid, owner_id uuid, opening numeric(19,4)) on commit drop;

insert into seed_accounts (id, owner_id, opening) values
    -- platform account that funds invoice advances
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a0', 5000000.00),
    -- 6-account load pool (same ids as load/seed-accounts.sql)
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000f1', 1000000.00),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000f2', 1000000.00),
    ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-0000000000f3', 1000000.00),
    ('10000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-0000000000f4', 1000000.00),
    ('10000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-0000000000f5', 1000000.00),
    ('10000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-0000000000f6', 1000000.00),
    -- duplicate-key pair
    ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000f7', 1000.00),
    ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000f8', 0.00),
    -- business account used by the load runner's invoice scenario
    ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000f9', 0.00),
    -- owned by the READ_ONLY persona (owner b1)
    ('40000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000b1', 5000.00),
    ('40000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000b1', 3200.00),
    -- owned by someone else (owner b2). 5000..02 is the deliberately drifted one.
    ('50000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000b2', 8000.00),
    ('50000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000b2', 1500.00),
    -- businesses with seeded invoices
    ('60000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000d1', 0.00),
    ('60000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000d2', 100.00),
    ('60000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-0000000000d3', 0.00);

insert into ledger_accounts (id, owner_id, balance, currency, version, created_at)
select id, owner_id, opening, 'USD', 0, now() - interval '30 days' from seed_accounts
on conflict (id) do nothing;

insert into ledger_entries (id, settlement_id, account_id, entry_type, amount, created_at)
select md5('opening-' || id::text)::uuid, null, id, 'OPENING', opening, now() - interval '30 days'
from seed_accounts where opening > 0
on conflict (id) do nothing;

-- ---------------------------------------------------------------- settlements
-- n, source, destination, amount, status, has_ref, minutes_ago
create temp table seed_settlements (n int, src uuid, dst uuid, amount numeric(19,4), status text,
                                    has_ref boolean, minutes_ago int) on commit drop;

insert into seed_settlements values
    (1,  '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',  25.00, 'CONFIRMED', true,  240),
    (2,  '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003',  40.00, 'CONFIRMED', true,  220),
    (3,  '10000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000004',  10.50, 'CONFIRMED', true,  200),
    (4,  '10000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000005',  75.00, 'CONFIRMED', true,  180),
    (5,  '10000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000006',  12.00, 'FAILED',    false, 160),
    (6,  '10000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000001',  33.00, 'FAILED',    false, 150),
    (7,  '40000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000002', 200.00, 'CONFIRMED', true,  140),
    (8,  '50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 150.00, 'CONFIRMED', true,  130),
    -- stranded: UNKNOWN with no external reference (Known gap 1). Reconciliation never loads these.
    (9,  '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000003',  60.00, 'UNKNOWN',   false, 120),
    (10, '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000004',  18.00, 'UNKNOWN',   false, 110),
    -- 11: the open settlement mismatch (the external record is seeded with a different amount)
    (11, '10000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000005',  90.00, 'CONFIRMED', true,  100),
    -- 12 and 13: previously mismatched, resolved by a human (the external record agrees now)
    (12, '10000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000006',  22.00, 'CONFIRMED', true,   90),
    (13, '10000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000001',  45.00, 'CONFIRMED', true,   80),
    -- 14: REVERSED is reachable in the state machine but no code path produces it (Known gap 3).
    -- Seeded by hand, with no external reference so reconciliation leaves it alone.
    (14, '10000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000002',   8.00, 'REVERSED',  false,  70),
    -- 15: PENDING, fresh. The stale-pending sweep finalizes it as UNKNOWN once its grace passes.
    (15, '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',   5.00, 'PENDING',   false,   0),
    -- invoice advances and repayment
    (16, '00000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 800.00, 'CONFIRMED', true,  60),
    (17, '00000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000002', 1600.00, 'CONFIRMED', true, 55),
    (18, '60000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 1640.00, 'CONFIRMED', true, 40),
    (19, '00000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000003', 400.00, 'CONFIRMED', true,  50);

insert into idempotency_keys (key, request_hash, response_snapshot, status, created_at, updated_at)
select md5('seed-key-' || n)::uuid,
       md5('seed-hash-' || n) || md5('seed-hash2-' || n),
       case when status = 'PENDING' then null else
         json_build_object('settlementId', md5('seed-settlement-' || n)::uuid, 'sourceAccountId', src,
                           'destinationAccountId', dst, 'amount', amount, 'currency', 'USD', 'status', status,
                           'externalRef', case when has_ref then 'MOCK-SEED-' || n else null end,
                           'createdAt', to_char(now() - make_interval(mins => minutes_ago), 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                           'updatedAt', to_char(now() - make_interval(mins => minutes_ago), 'YYYY-MM-DD"T"HH24:MI:SS"Z"'))::text
       end,
       case when status = 'PENDING' then 'IN_PROGRESS' else 'COMPLETED' end,
       now() - make_interval(mins => minutes_ago), now() - make_interval(mins => minutes_ago)
from seed_settlements
on conflict (key) do nothing;

insert into settlements (id, idempotency_key, source_account_id, destination_account_id, amount, currency, status,
                         external_ref, created_at, updated_at)
select md5('seed-settlement-' || n)::uuid, md5('seed-key-' || n)::uuid, src, dst, amount, 'USD', status,
       case when has_ref then 'MOCK-SEED-' || n else null end,
       now() - make_interval(mins => minutes_ago), now() - make_interval(mins => minutes_ago)
from seed_settlements
on conflict (id) do nothing;

-- Money moved by CONFIRMED settlements, and by the REVERSED one (it was CONFIRMED before it was reversed).
insert into ledger_entries (id, settlement_id, account_id, entry_type, amount, created_at)
select md5('seed-debit-' || n)::uuid, md5('seed-settlement-' || n)::uuid, src, 'DEBIT', amount,
       now() - make_interval(mins => minutes_ago)
from seed_settlements where status in ('CONFIRMED', 'REVERSED')
on conflict (id) do nothing;

insert into ledger_entries (id, settlement_id, account_id, entry_type, amount, created_at)
select md5('seed-credit-' || n)::uuid, md5('seed-settlement-' || n)::uuid, dst, 'CREDIT', amount,
       now() - make_interval(mins => minutes_ago)
from seed_settlements where status in ('CONFIRMED', 'REVERSED')
on conflict (id) do nothing;

-- Stored balance = net of entries, for every account.
update ledger_accounts a set balance = coalesce((
    select sum(case when e.entry_type = 'DEBIT' then -e.amount else e.amount end)
    from ledger_entries e where e.account_id = a.id), 0);

-- The one deliberately drifted account: someone edited the balance by hand (+25.00).
update ledger_accounts set balance = balance + 25.00 where id = '50000000-0000-0000-0000-000000000002';

-- ---------------------------------------------------------------- read model + outbox history
insert into settlement_read_model (settlement_id, source_account_id, destination_account_id, amount, currency, status,
                                   updated_at)
select md5('seed-settlement-' || n)::uuid, src, dst, amount, 'USD', status, now() - make_interval(mins => minutes_ago)
from seed_settlements
on conflict (settlement_id) do nothing;

insert into outbox_events (id, aggregate_type, aggregate_id, topic, payload, created_at, published_at)
select md5('seed-out-req-' || n)::uuid, 'SETTLEMENT', md5('seed-settlement-' || n)::uuid, 'settlement.requested',
       json_build_object('settlementId', md5('seed-settlement-' || n)::uuid, 'sourceAccountId', src,
                         'destinationAccountId', dst, 'amount', amount, 'currency', 'USD')::text,
       now() - make_interval(mins => minutes_ago), now() - make_interval(mins => minutes_ago)
from seed_settlements
on conflict (id) do nothing;

insert into outbox_events (id, aggregate_type, aggregate_id, topic, payload, created_at, published_at)
select md5('seed-out-final-' || n)::uuid, 'SETTLEMENT', md5('seed-settlement-' || n)::uuid,
       case status when 'CONFIRMED' then 'settlement.confirmed' when 'REVERSED' then 'settlement.confirmed'
                   when 'FAILED' then 'settlement.failed' else 'settlement.unknown' end,
       json_build_object('settlementId', md5('seed-settlement-' || n)::uuid, 'sourceAccountId', src,
                         'destinationAccountId', dst, 'amount', amount, 'currency', 'USD')::text,
       now() - make_interval(mins => minutes_ago), now() - make_interval(mins => minutes_ago)
from seed_settlements where status <> 'PENDING'
on conflict (id) do nothing;

-- ---------------------------------------------------------------- reconciliation history
insert into reconciliation_runs (id, started_at, finished_at, records_checked, mismatches_found, status) values
    ('70000000-0000-0000-0000-000000000001', now() - interval '25 minutes', now() - interval '25 minutes' + interval '1 second', 9, 0, 'COMPLETED'),
    ('70000000-0000-0000-0000-000000000002', now() - interval '20 minutes', now() - interval '20 minutes' + interval '1 second', 10, 1, 'COMPLETED'),
    ('70000000-0000-0000-0000-000000000003', now() - interval '15 minutes', now() - interval '15 minutes' + interval '1 second', 11, 2, 'COMPLETED'),
    ('70000000-0000-0000-0000-000000000004', now() - interval '10 minutes', now() - interval '10 minutes' + interval '1 second', 12, 1, 'COMPLETED'),
    ('70000000-0000-0000-0000-000000000005', now() - interval '5 minutes',  now() - interval '5 minutes' + interval '1 second', 14, 1, 'COMPLETED')
on conflict (id) do nothing;

insert into reconciliation_mismatches (id, run_id, settlement_id, internal_state, external_state, details,
                                       resolution_status, resolved_at, created_at) values
    ('71000000-0000-0000-0000-000000000011', '70000000-0000-0000-0000-000000000005', md5('seed-settlement-11')::uuid,
     'CONFIRMED', 'CONFIRMED', 'External record amount/currency (99.0000 USD) does not match internal (90.0000 USD)',
     'OPEN', null, now() - interval '5 minutes'),
    ('71000000-0000-0000-0000-000000000012', '70000000-0000-0000-0000-000000000002', md5('seed-settlement-12')::uuid,
     'CONFIRMED', 'FAILED', 'Internal status CONFIRMED does not match external status FAILED',
     'RESOLVED', now() - interval '12 minutes', now() - interval '20 minutes'),
    ('71000000-0000-0000-0000-000000000013', '70000000-0000-0000-0000-000000000003', md5('seed-settlement-13')::uuid,
     'CONFIRMED', null, 'No external record found for reference MOCK-SEED-13 after grace period',
     'RESOLVED', now() - interval '8 minutes', now() - interval '15 minutes')
on conflict (id) do nothing;

-- ---------------------------------------------------------------- ledger mismatches
insert into ledger_mismatches (id, account_id, stored_balance, computed_balance, details, resolution_status,
                               resolved_at, created_at)
select '72000000-0000-0000-0000-000000000001', id, balance, balance - 25.00,
       'Stored balance ' || balance || ' does not match net of ledger entries ' || (balance - 25.00),
       'OPEN', null, now() - interval '3 minutes'
from ledger_accounts where id = '50000000-0000-0000-0000-000000000002'
on conflict (id) do nothing;

insert into ledger_mismatches (id, account_id, stored_balance, computed_balance, details, resolution_status,
                               resolved_at, created_at)
select '72000000-0000-0000-0000-000000000002', id, balance + 10.00, balance,
       'Stored balance ' || (balance + 10.00) || ' does not match net of ledger entries ' || balance,
       'RESOLVED', now() - interval '18 minutes', now() - interval '22 minutes'
from ledger_accounts where id = '50000000-0000-0000-0000-000000000001'
on conflict (id) do nothing;

-- ---------------------------------------------------------------- invoices, advances, fraud
insert into invoices (id, business_account_id, customer_reference, amount, currency, due_date, status,
                      external_source_ref, created_at, updated_at) values
    ('80000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 'CUST-NORTHWIND-1042', 1200.00, 'USD',
     now() + interval '20 days', 'ISSUED', 'SEED-INV-1', now() - interval '2 hours', now() - interval '2 hours'),
    ('80000000-0000-0000-0000-000000000002', '60000000-0000-0000-0000-000000000001', 'CUST-ACME-7781', 1000.00, 'USD',
     now() + interval '25 days', 'FINANCED', 'SEED-INV-2', now() - interval '65 minutes', now() - interval '60 minutes'),
    ('80000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000002', 'CUST-GLOBEX-3310', 2000.00, 'USD',
     now() + interval '10 days', 'REPAID', 'SEED-INV-3', now() - interval '70 minutes', now() - interval '40 minutes'),
    ('80000000-0000-0000-0000-000000000004', '60000000-0000-0000-0000-000000000003', 'CUST-INITECH-9921', 500.00, 'USD',
     now() - interval '10 days', 'OVERDUE', 'SEED-INV-4', now() - interval '60 minutes', now() - interval '45 minutes')
on conflict (id) do nothing;

insert into advances (id, invoice_id, amount_advanced, fee, disbursed_settlement_id, repaid_settlement_id, status,
                      created_at) values
    ('81000000-0000-0000-0000-000000000002', '80000000-0000-0000-0000-000000000002', 800.00, 20.00,
     md5('seed-settlement-16')::uuid, null, 'DISBURSED', now() - interval '60 minutes'),
    ('81000000-0000-0000-0000-000000000003', '80000000-0000-0000-0000-000000000003', 1600.00, 40.00,
     md5('seed-settlement-17')::uuid, md5('seed-settlement-18')::uuid, 'REPAID', now() - interval '55 minutes'),
    ('81000000-0000-0000-0000-000000000004', '80000000-0000-0000-0000-000000000004', 400.00, 10.00,
     md5('seed-settlement-19')::uuid, null, 'DISBURSED', now() - interval '50 minutes')
on conflict (id) do nothing;

insert into fraud_assessments (id, invoice_id, score, decision, reasons, created_at) values
    ('82000000-0000-0000-0000-000000000002', '80000000-0000-0000-0000-000000000002', 0.000, 'ALLOW', null, now() - interval '61 minutes'),
    ('82000000-0000-0000-0000-000000000003', '80000000-0000-0000-0000-000000000003', 0.000, 'ALLOW', null, now() - interval '56 minutes'),
    ('82000000-0000-0000-0000-000000000004', '80000000-0000-0000-0000-000000000004', 0.300, 'ALLOW', 'new_account_high_advance', now() - interval '51 minutes')
on conflict (id) do nothing;

-- ---------------------------------------------------------------- audit log
insert into audit_log (id, actor_id, action, target_table, target_id, outcome, created_at) values
    ('90000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000c2', 'TRIGGER_RECONCILIATION_RUN',
     'reconciliation_runs', '70000000-0000-0000-0000-000000000003', 'SUCCESS', now() - interval '15 minutes'),
    ('90000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000c2', 'RESOLVE_RECONCILIATION_MISMATCH',
     'reconciliation_mismatches', '71000000-0000-0000-0000-000000000012', 'SUCCESS', now() - interval '12 minutes'),
    ('90000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-0000000000c1', 'RESOLVE_RECONCILIATION_MISMATCH',
     'reconciliation_mismatches', '71000000-0000-0000-0000-000000000013', 'SUCCESS', now() - interval '8 minutes'),
    ('90000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-0000000000c1', 'RESOLVE_LEDGER_MISMATCH',
     'ledger_mismatches', '72000000-0000-0000-0000-000000000002', 'SUCCESS', now() - interval '18 minutes'),
    ('90000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-0000000000c3', 'RESOLVE_LEDGER_MISMATCH',
     'ledger_mismatches', '72000000-0000-0000-0000-000000000001', 'DENIED', now() - interval '2 minutes')
on conflict (id) do nothing;
