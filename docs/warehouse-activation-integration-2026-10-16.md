# Warehouse backend integration on `Sardor`

This branch merges the `mirzo` warehouse backend and closes the concrete integration defects found in the 16 October review. It is intended for a draft merge request to `main`; the activation path must remain disabled until the database tests and the business cutover evidence are accepted.

## Implemented

- An activation plan selects one completed source import, its manifest hash, snapshot date, cutover date, warehouse and posting date. Apply locks the plan, checks the approved source and preview hashes again, posts eligible deltas once, and records the plan ID in an immutable journal.
- Journal replay requires the same plan and scope. An omitted posting date uses the stored date. Per-row posting keys are bounded SHA-256 values, so long canonical source keys fit the posting batch column.
- Source preview compares owner, stock category, unit and UZS currency; blocks duplicate source positions, unproven or missing physical stock, negative positions, value-only rows and activity after the approved snapshot. Unsupported identities require reconciliation rather than automatic opening stock.
- Approved opening rules must use UZS. The posting service rejects a currency that conflicts with its selected rule.
- Account-aware issues serialize by account position and require committed availability for that account, owner, stock category, unit and currency. Missing account evidence gives zero availability.
- A newer completed source snapshot may be imported. Canonical source keys may recur in a later snapshot but remain unique across same-date addenda. Source reports use the latest completed snapshot and do not add earlier snapshot balances.
- Completed imports, their entries, and approved plan evidence are protected by database triggers.
- After integrating `origin/main` at `49eaaf7f`, its published manual-receipt migration remains V16. Warehouse migrations run as V17–V33. V34 aligns warehouse tables with inherited entity fields; V35 seeds the default department types. Later source snapshots use import-scoped row IDs, so recurring source keys do not collide.
- Native movements without a linked account effect remain under the unspecified account in source-based reports. The report does not infer an account from material and warehouse alone.
- A legacy single-item stock movement request now copies its item material, quantity, cost and stock location into the movement before UOM validation. Physical-count freeze tests use ordinary stock receipts so they exercise the freeze rather than the managed-adjustment guard.

## Verification and merge gate

- `mvn -q -DskipTests package` passed after these changes.
- Activation, account posting, account isolation and preview unit tests passed in focused runs.
- After Docker recovery, a clean PostgreSQL 16 run applied and validated V1–V33. The V13→V14 posting and V15→V16 manual-receipt upgrade checks passed. The combined activation, posting and manual-operation selection passed 63 tests with no failures.
- The PostgreSQL 17 warehouse-account class passed 11 tests, including a million-movement report under its 15-second per-statement limit. The report now aggregates each source movement once instead of expanding it into separate opening and period rows.
- The approval-flow test fixture that blocked the earlier full run now inserts its required default company; its 10 tests pass. The attachment service test fixture now inserts that same company before creating its viewer; all 19 `AttachmentServiceTest` tests pass, including the previously blocked viewer case.
- A later broad test run reached 541 classes and 3,415 tests before it was stopped after finding multiple old fixture assumptions. Fixes since that run cover the transfer actor contract, published migration count, generated code assertions, PostgreSQL entity scanning and warehouse accounting fixtures. A fresh 109-test focused run applied V1–V35 and passed its migration replay, migration validation, warehouse inventory adjustment, production safety, RFQ grouping and material catalog checks. Follow-up runs passed the material availability, monthly demand and PostgreSQL RFQ concurrency classes. The older procure-to-pay flow remains outside this warehouse patch; its receipt and service acceptance expectations need separate work. The full suite has not passed on this revision, so the MR requires a green CI run and review before merging.
- Finance must approve the selected source manifest/cutover, posting rules and unresolved positions. The backend intentionally does not activate ambiguous owner/currency identities or reconcile post-snapshot events automatically.
- Follow-up warehouse checks passed 15 stock movement tests, the PostgreSQL transfer baseline test, all 7 physical-count concurrency tests, and all 13 supplier portal integration tests.
