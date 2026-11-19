# 1C movements in the normal warehouse module

The stock-movement search and detail APIs combine operational MM movements with a
projection of the verified 1C archive. The projection uses active accounting legs
for material accounts 10xx, the same account scope as the exact opening snapshot.
Document lines and accumulation registers remain supporting source evidence;
they are not added a second time as receipts or issues.

Within a document and effective date, postings are netted per material and
warehouse across accounts. Account reclassifications within one warehouse cancel.
Warehouse transfers have distinct incoming and outgoing warehouse rows. An
opposite warehouse is supplied only when the source document has one unambiguous
opposite warehouse for that material. Inactive postings and special stock outside
10xx remain in source history and do not enter the owned-stock reporting ledger.

## Stock boundary

V9 collects statistics on the newly populated projection before its correlated
warehouse-pairing UPDATE. Without those statistics, PostgreSQL 17 with the real
source-key distribution chose the date index and repeatedly scanned unrelated
movements from the same date. The normalized 524,583-row source benchmark uses
the document index after ANALYZE and finishes pairing in 19.091 seconds. The
bulk projection is staged while the current backend remains online. A standalone
entry point uses the built image's Flyway libraries and migrations, targets only
V9, and never starts Spring or application schedulers. The deployment helper
requires a fresh verified backup and the exact serving image/configuration,
checks the independently reproduced source digest and all seven current stock
fingerprints, then permits the normal backend replacement. Failed staging leaves
the serving backend running. Later migrations run during the final startup; the
deployment readiness window is ten minutes.
The original stalled V9 was transactionally rolled back to V8 before this source
correction; deployment must still verify the successful Flyway prefix and reject
any existing V9 checksum that differs from the reviewed file.

Historical rows live in `warehouse_imported_movements`. No historical receipt,
issue, transfer or cancellation is executed by MM's stock or costing services.
They are already represented by the posted opening snapshot. Normal new MM
movements retain their existing workflow and stock effects.

For a historical report starting before the snapshot date, its opening equals
the verified snapshot minus 1C changes on or after the requested start date.
Re-adding changes through the report end reconstructs the closing balance. The
snapshot therefore appears exactly once, including in periods crossing the
boundary. Current reports use the existing operational ledger directly.

Legacy references without current MM catalog links retain original 1C names and
codes in the movement list and reports. Their report identities are deterministic
and company scoped. Quantities with unavailable unit metadata remain separated
by source material rather than being pooled into a fictitious unit.

Historical rows expose signed decimal strings and a source-record link. Posting,
reversing, editing, deleting and submitting these imported rows are rejected;
stock corrections are new MM transactions. This restriction preserves both the
source evidence and today's inventory.

## Verification

V9 saves before/after row counts and hashes for current stock balances, batches,
cost layers, costing movements, operational movements and allocations. The
administrator endpoint `/api/migration/warehouse-history/projection-verification`
returns those fingerprints, the current fingerprints and a deterministic semantic
digest of the projected source rows. The digest can be independently reproduced
from the immutable local export.

PostgreSQL tests cover account-leg cancellation, transfers, inactive and special
stock exclusion, mixed MM/source pagination, effective dates, tenant/company and
warehouse permissions, unchanged stock and reservations, report periods before
and across the snapshot, legacy references and re-post prevention.
Standalone runner tests verify the V9 target boundary in the presence of V10,
checksum rejection and transactional rollback. Deployment helper tests cover
source/fingerprint mismatches, changed images, extraction boundaries, failed
staging and the reviewed already-installed V9 path.
The private staging receipt preserves the initial stock boundary and migration
checksum. Installed V9 still requires source reconciliation; only a matching
successful receipt permits legitimate later stock changes on ordinary releases.
If staging commits but a later check fails before backend replacement, keep the
old service running and inspect the original private task state. A new backup can
reject an old JAR missing installed V9; do not repair checksums or bypass proof to
retry that transition.

## Reviewed issue sources

The Issue Material picker reads `/api/workspaces/warehouse-execution/issue-documents/1c`.
Its review queue contains unposted, unmarked `Требование номенклатуры`
(`_document4470`) headers at the authorized sending warehouse. The history tab
includes posted/deleted requirements, archived `Списание номенклатуры`
(`_document199`) documents and requirements whose linked MM transfer is complete
or cancelled. Empty or unmapped requests remain visible with blocking reasons.
Both headers and lines must belong to completed imports in the selected tenant
and company. “Unposted” is a snapshot flag, not a claim of a live remaining balance.

The populated unposted requirements in the checked export identify receiving
warehouses on their lines (`_fld4526rref`). After a manager checks current evidence
and records that the entire requested quantity remains unissued, Prepare creates
one **DRAFT transfer**, preserving the sending warehouse, receiving warehouse,
materials and exact quantities. It does not create a consumption issue, execute a
historical posting, reserve inventory, submit or approve the draft. Normal transfer
approval, stock-position selection and dispatch follow separately. Requests with
no distinct mapped destination or several destinations require reconciliation;
this adapter does not infer their purpose or split them into unrelated issues.

Preparation rejects posted/deleted/unknown source states, incomplete imports,
existing source movements, related posted requirement/material documents,
unmapped materials, unsupported units/conversions and invalid quantities. It
never estimates a residual quantity from partially fulfilled or ambiguous evidence.
V48 stores the source fingerprint, reviewer, review note and unique source-to-MM
link. A source-row lock serializes preparation; retries require the same idempotency
key and payload. Creating the draft, source link and replay record is transactional.
Later draft edits cannot change warehouses or exceed source quantities aggregated
by material. Cancellation does not release the unique source for replay.

`IssueSourceDocumentServiceTest` exercises PostgreSQL scope/filtering, decimal
precision, reconciliation blockers, concurrency, idempotency, rollback and edit
limits. `TransferSubmissionPostgresTest` also covers real JPA/JDBC draft creation,
unchanged stock and the normal independent approval workflow. Frontend coverage
in `warehouse-issue-sources.spec.ts` checks read-only history, blocked requests,
review confirmation, retry identity and desktop/mobile rendering.
