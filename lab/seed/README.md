# Lab seed

Deterministic data for the public Lab sandbox (`docs/lab.md`). Sandbox only: the personas' passwords are fixed
and public, and `LabSafetyGuard` refuses to start the demo profile against any database whose name does not end in
`_lab`.

## Files

- `lab-seed.sql` loads the whole seed. Fixed ids, `on conflict do nothing`, loaded inside one transaction.
- `scenarios.json` is the catalog the UI renders (`GET /lab/scenarios`). `ScenariosCatalogTest` checks that every doc
  link, class and method it names exists.

## How it is built

Accounts are declared with an opening balance in a temp table. Each account with money gets one `OPENING` ledger
entry. Settlements are declared in a second temp table, and the DEBIT/CREDIT entries, idempotency keys, read-model
rows and outbox rows are all derived from it. Finally every stored balance is set to the net of its entries, except
account `50000000-0000-0000-0000-000000000002`, drifted by +25.00 on purpose (the open ledger mismatch).
`LabSeedIntegrityTest` fails if any other account disagrees with its entries.

| What | Where |
| --- | --- |
| 6-account load pool, 1,000,000 each | `10000000-...-0001` to `-0006` |
| Duplicate-key pair (1000 / 0) | `20000000-...-0001`, `-0002` |
| Load runner's business account | `30000000-...-0001` |
| READ_ONLY persona's accounts (owner `...b1`) | `40000000-...-0001`, `-0002` |
| Another owner's accounts (`...b2`), the second is the drifted one | `50000000-...-0001`, `-0002` |
| Platform account | `00000000-...-0001` |
| Settlements in every status, 19 in all | `n` in the temp table; id is `md5('seed-settlement-' \|\| n)::uuid` |
| `REVERSED` (seeded by hand, no code path produces it) | settlement 14 |
| Open settlement mismatch | settlement 11 (its external record is rebuilt with amount 99.00) |
| Personas (password is `<username>-sandbox`) | `lab-admin`, `lab-support`, `lab-readonly` |

Settlements with an `external_ref` get a matching record in `MockExternalSystem` after each load, because that store
is in memory (`LabResetService` rebuilds it from the loaded rows).

## Reset by hand

Normally `POST /lab/reset` (once a minute) or the 30 minute auto-reset does this. To do it manually against the
sandbox database only:

```
docker compose -f infra/docker-compose.lab.yml exec postgres psql -U settlement_engine -d settlement_engine_lab
```

then truncate the tables listed in `LabResetService.DATA_TABLES` and run `\i lab-seed.sql` inside a transaction. Restart
the backend afterwards so its in-memory external store is rebuilt.
