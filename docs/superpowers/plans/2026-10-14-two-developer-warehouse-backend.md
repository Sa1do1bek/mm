# Warehouse Backend Two Developer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. The two human developers can use the same tasks and review gates directly.

**Goal:** Deliver the backend warehouse roadmap in reconciled releases without competing stock mutation paths, migration collisions, or duplicate source activation.

**Architecture:** One transaction boundary records physical, account, cost, origin, audit, and outbox effects. Existing MM workflow services call that boundary; imported 1C records remain immutable evidence until an approved, idempotent activation creates a missing native opening. Each later warehouse workflow uses the same posting and reconciliation contracts.

**Tech Stack:** Java, Spring Boot, PostgreSQL, Flyway, Maven, JUnit, and PostgreSQL Testcontainers.

**Spec:** `MM_Warehouse_Roadmap_Technical_Specification_2026-10-14.docx`, version 1.0, provided by the product team. Share the same copy and its source-evidence manifest with both developers before implementation. Sections 4-7 and 14-17 govern the first release.

**Individual checklists:** [Developer A tasks and main merge schedule](2026-10-14-warehouse-backend-developer-a.md) and [Developer B tasks and main merge schedule](2026-10-14-warehouse-backend-developer-b.md). Both explain when to push a feature branch, when a reviewed request may merge into `main`, and when a backend milestone is ready for coordinated release.

## Global Constraints

- Backend only. Frontend workflows and translations in the specification are integration contracts for a later coordinated release, not deliverables of these backend merge requests.
- Begin from the same reviewed remote commit. At planning time `origin/main` is `fbd3543c`; the local `main` branch is older. Refresh and verify the remote commit before either developer branches.
- Existing operational owned-stock opening stays authoritative. Imported account/history rows remain immutable; activation posts only an approved missing delta.
- Preserve tenant and company scope, warehouse/owner/account permissions, exact decimal values, original source identity, partial execution, period guards, allocation evidence, and approved reversals.
- Reuse the existing stock mutation, costing, allocation, idempotency, audit, and outbox mechanisms. No workflow may create a second independent stock writer.
- Add Flyway migrations after installed V1-V13. Do not edit installed migration checksums. Assign every new version to one merge request before work begins; reassign if merge order changes.
- Developer A uses V14 for A1 posting evidence. Developer B reported V13 as the last migration on `mirzo`; B must use a later free version for any new migration. B's original task plan remains unchanged.
- The specification calls `.gitlab-ci.yml` and `Dockerfile` protected deployment files. Keep those out of implementation merge requests and record any genuine dependency for a separate project decision.
- The engineering choices in `docs/warehouse-posting-contract.md` allow disabled backend capability and read-only preview work to proceed. D01, D02, D04, D07, and D08 still require business confirmation before affected financial delivery, source activation or posting commands are enabled. D03, D05, and D06 gate their later phases.
- No production import, activation, deployment, or company enablement is part of a code merge. Each requires its own reviewed runbook, backup, preview, and business sign-off.

## Review Focus

Each item below must have a named test in its owning merge request.

1. Two account or owner positions at one material/warehouse key: an issue allocates only the selected eligible position and never spends the other position.
2. Two concurrent partial commands: the committed result cannot exceed stock, cost-layer remainder, source-origin remainder, or approved document quantity.
3. Successful posting followed by timeout and identical retry: one effect and one receipt; same key with changed payload is a conflict.
4. A source value-only, negative, or disputed position: reportable with original sign and value but never available as physical stock.
5. A nullable location/bin/batch key on PostgreSQL: no duplicate logical stock position despite SQL `NULL` uniqueness behavior.

## Team Operating Agreement

| Rule | Developer A | Developer B |
| --- | --- | --- |
| Primary ownership in Phase 1 | Posting context, account/stock/cost effects, mutation-path adaptation | Source classification/activation, account reports, independent reconciliation |
| Primary ownership in Phase 2 | Receipt, issue, return, and reversal adapters | Grouped transfers, transit, inventory adjustment and write-off adaptation |
| Shared files | Authors agreed posting contract and shared stock services | Reviews every shared-service change; does not create a parallel writer |
| Source/migration files | Reviews source identity and activation changes | Authors activation/reconciliation migration after its number is reserved |
| Review | Reviews B's SQL, scope, source and workflow changes | Reviews A's posting effects, locks, allocations and migration changes |

Use separate branches and working directories. Developer A continues on `Sardor`. Developer B continues the previously shared Developer B plan and their existing branch/MR workflow; B does not need to restart or rebase onto `Sardor`. Neither developer pushes directly to `main`. Once B's relevant work is ready, A fetches its exact remote branch and base commit. If B's request is already merged, A updates `Sardor` from `origin/main`; otherwise A reviews and merges B's branch into `Sardor` and coordinates the two requests so the same changes are not integrated twice. A resolves conflicts, both developers review the combined result, and affected tests run before `Sardor` targets `main`. Never pull an unspecified branch or overwrite either branch's work.

**Parallel work and integration sequence:** A develops A1 while B develops B1 under the previously shared plan. At the first ready milestone, A brings B1 from its branch or from `origin/main` into `Sardor`, adapts overlap and tests the combined result. A1/B1 can then merge to `main` through reviewed requests in the order their dependencies allow. A2 follows A1 on `main`; B2 activation still waits for A2's reviewed posting interface. Repeat this integration at later safe milestones rather than waiting until every roadmap task is finished. A3/B3 and A4/B4 may develop in parallel where interfaces are agreed.

**Shared-contract rule:** A owns changes to `StockBalanceMutationService`, `InventoryCostingService`, `StockMovementAllocationWriter`, `StockMovementDocumentService`, `TransferPostingService`, and `WarehouseReceiptPostingService` on `Sardor`. B follows their existing task ownership. If both branches touch a shared file, resolve it in the integration merge and review the resulting behavior together.

**Merge gate for every request:** focused tests pass; PostgreSQL tests pass when SQL, locking, uniqueness, or monetary values change; migration upgrades from V13 on a representative copy; no cross-scope read/write path; exact before/after effect explanation; no unexplained stock/account/cost difference; reviewer approval from the other developer.

## Gate G0 Joint Posting Examples and Baseline

**Owners:** Both developers for the implementation contract; warehouse and accounting owners for pilot activation. **Merge the reviewed engineering contract before state-changing Phase 1 work; obtain business confirmation before enabling affected commands.**

- [ ] Pin the remote backend revision, list installed Flyway versions, and record the current stock, cost-layer, allocation, source-account, and source-hash fingerprints by pilot company.
- [ ] Build a mutation-path register for receipt, transfer, issue, return, reservation, quarantine, inventory adjustment, opening, and fixed-asset handoff. Identify the stock, cost, allocation, audit, outbox, and account effects of each existing caller.
- [x] Record engineering expectations for five examples: owned 1010 issue; one item split across two accounts; partial transfer with a discrepancy; historical-origin return; and value-only source balance. Obtain business sign-off on financial effects before activation.
- [x] Record a first-implementation choice for D01 and fail-closed defaults for D02, D04, D07 and D08. Keep owner confirmation and scope-specific rules visible as activation gates.
- [ ] Agree the data contract: physical position key, account position key, owner/holder/category/disposition dimensions, source identity, posting date and rule version, allocation remainder, and reversal link.
- [ ] Reserve the first migration number only for the first merged schema request. Reserve later numbers immediately before their requests are ready; never let both branches create the same `Vnn` file.

**Output:** A reviewed `docs/warehouse-posting-contract.md` containing the examples, authority matrix, position/effect invariants, mutation-path register, and pilot scope. This is a decision artifact, not a production migration.

## Release 1 Part A Phase 1 Stock and Accounting Foundation

### PR 1A Posting Evidence and Rule Contract — Developer A

**Depends on:** G0. **Merge before:** PR 1B apply, PR 1C, and Phase 2 adapters.

**Files:** Add focused entities/services under `src/main/java/com/enterprise/mm/warehouse/posting/`; add the next unallocated Flyway migration under `src/main/resources/db/migration/`; extend existing `IdempotencyService` and `RoleDataScopeService` only where the new command contract needs them. Keep the current operational tables and V1-V13 intact.

**Produces:** A scoped posting-batch/effect record, versioned approved rule snapshot, exact decimal and canonical payload hashes, and a single application interface for physical/account/cost effects. The first request may create the evidence model and preview without switching all callers; do not claim account-aware operational completion until PR 1C.

- [ ] Write PostgreSQL tests for scoped unique command identity, same-key changed-payload conflict, nullable stock-key uniqueness, effect-type quantity/value constraints, and rollback of an incomplete batch. Verify each fails before implementation.
- [ ] Add the additive schema and model. Use explicit columns for scope, identity, posting date, rule version, quantity, amount, currency, and effect type; retain JSON snapshots only for ancillary context. Reject invalid physical effects without quantity/unit and value-only effects presented as available stock.
- [ ] Implement rule selection by company, book, operation/context, and posting date, with an immutable selected rule snapshot. Block ambiguous or unapproved rules.
- [ ] Implement a transaction-scoped posting entry point whose identical committed retry returns the stored receipt before stale-document-version rejection. A changed payload under the same key fails without mutation.
- [ ] Run focused tests, the existing stock concurrency tests, and migration upgrade tests. Review the exact SQL and explain the position/effect invariants in the merge request.

**Acceptance:** P1 AT02, AT05, and AT06 at the contract level; E2E 06 and 07 at the posting boundary.

### PR 1B Source Classification and Activation Preview — Developer B

**May start alongside PR 1A.** The preview is read-only and does not write native stock.

**Files:** Extend `warehouse/history` and `warehouse/reports` source projection/query code; add focused classification/preview services under `warehouse/activation/`; reuse the existing V12 account entries and V13 source links. Add no schema migration until its exact need and version are agreed with A.

**Produces:** A company-scoped classification of each source position as already represented, eligible missing stock/holding, historical only, value only, negative/disputed, or unresolved. Preview displays source quantity/value, native representation, post-snapshot native deltas, proposed activation delta, source hashes, and reasons for blocks.

- [ ] Write tests for canonical source identity, duplicate names/codes, zero references, folder balances, value-only and negative positions, and two unresolved transfer destinations. Verify failures before implementation.
- [ ] Implement a deterministic reconstruction using one recorded source snapshot/cutover boundary and explicit event ownership. A backdated or corrected source event cannot silently cross the boundary.
- [ ] Add read/preview endpoints with tenant, company, warehouse, account, and export scope checks. Preview performs no reservation or posting.
- [ ] Compare preview totals by full account/material/warehouse/owner/unit/currency keys against an independent fixture, without netting positive and negative mismatches.
- [ ] Merge after review while PR 1A remains in progress if no shared posting files or migration numbers collide.

**Acceptance:** Preview portions of P1 AT03, AT04, and AT07. This request does not satisfy activation apply.

### PR 1C Account-Aware Native Posting — Developer A

**Depends on:** PR 1A. B reviews every changed mutation path.

**Files:** Adapt `StockBalanceMutationService`, `InventoryCostingService`, `StockMovementAllocationWriter`, `StockMovementDocumentService`, `TransferPostingService`, `WarehouseReceiptPostingService`, and affected repository queries through the shared posting context. Add targeted tests next to the existing stock, costing, allocation, transfer, and receiving tests.

- [ ] Write a failing issue test for one item/warehouse with account 1010 and another account: the selected account, layer, and physical availability decrease once; the other account does not.
- [ ] Add a deterministic lock order and enforce expected document version inside the locked transaction. Cover concurrent partial requests with PostgreSQL tests.
- [ ] Route existing receipt, issue, transfer, return, inventory adjustment, reservation release, and quarantine mutations through the documented context or explicitly block unsupported new position types. Keep legacy owned-stock behavior compatible.
- [ ] Record physical, account, cost, allocation, audit, and outbox evidence atomically. Confirm a failed account/cost effect rolls back stock.
- [ ] Verify quantity at seven decimals, authoritative original amount, zero-value stock, and explicit final-layer residual behavior. No silent rounding or invented unit conversion.
- [ ] Run the existing stock/cost/transfer/receipt suites and the new PostgreSQL concurrency tests before merge.

**Acceptance:** P1 AT01, AT02, AT05, AT06; E2E 02, 06, and 07 for the enabled position types.

### PR 1D Activation Apply and Account Reconciliation — Developer B

**Depends on:** PR 1B and PR 1C. A reviews every use of the posting entry point.

**Files:** Add activation journal/receipt and approval storage in the next reserved Flyway migration; add activation apply under `warehouse/activation/`; update `WarehouseAccountReportQuery` and account report tests; add independent reconciliation query/service under `warehouse/reconciliation/`.

- [ ] Write failing PostgreSQL tests: identical activation returns one receipt; changed source hash, company, cutover, or payload conflicts; partial failure leaves no physical/account/cost effect.
- [ ] Apply only the approved missing delta through the shared posting entry point. Keep original `warehouse_account_entries`, history, and source profile content unchanged.
- [ ] Replace the native report's `Счёт не указан (MM)` branch for **new, proven** posting effects with their resolved account. Keep old unclassified native events explicit; never infer their account.
- [ ] Reconstruct stock, account, layers, transit, and source coverage independently from approved opening plus signed effects. Compare keyed quantities and values, not only grand totals.
- [ ] Verify source hashes before/after and produce a receipt with preview hash, approvals, posting identity, and exact differences. Test full report/filter/export scope.

**Acceptance:** P1 AT01, AT03, AT04, AT07; E2E 09 and 10. Exit: no unexplained difference in enabled pilot keys, with unresolved legacy keys visible and non-executable.

## Release 1 Part B Phase 2 Everyday Backend Operations

Start only after Phase 1's pilot posting/reconciliation contract passes. Each operation is its own merge request; both developers refresh against the latest integration commit between requests.

### PR 2A Receipt and Issue Context — Developer A

**Files:** `WarehouseReceiptPostingService`, `IssueRequestService`, their controllers/DTOs and tests, and only the shared posting interfaces already agreed in Phase 1.

- [ ] Test 100 ordered units with 80 accepted, 10 damaged/held under the approved policy, and 10 outstanding; prove PO remainder, physical/disposition, account and cost effects.
- [ ] Add approved standalone receipt origin and account/value evidence without bypassing supplier, quality, unit, or valuation checks. Keep services out of physical stock.
- [ ] Make posted issue quantity—not request approval or closure—drive stock/account reduction. Release unused reservation on approved partial-demand closure.
- [ ] Verify role/warehouse/account scope, closed period, partial execution, exact values, and identical retry.

**Acceptance:** P2 AT01 and AT04.

### PR 2B Grouped Transfers and Transit — Developer B

**Files:** `TransferDocumentService`, `TransferPostingService` adapter points, transfer controllers/DTOs and tests; new parent-group model/service in a focused `warehouse/transfergroup/` package with its reserved migration.

- [ ] Test a three-destination request becoming three same-company child transfers with stable source-line mappings and aggregate progress.
- [ ] Keep exact dispatch allocations through two partial receipts; classify missing/damaged/outstanding quantity as transit or reviewed exception rather than silently closing it.
- [ ] Enforce source dispatch and destination receipt permissions independently. Keep the two unresolved source destinations blocked pending review.
- [ ] Test canceling an unexecuted remainder without reversing executed dispatch. Reconcile quantity/value across source, transit, destination, and exceptions.

**Acceptance:** P2 AT02 and AT03.

### PR 2C Historical-Origin Returns and Reversal — Developer A

**Files:** `MaterialReturnService`, return controller/DTOs, `StockMovementDocumentService` adapter, origin-link storage in a reserved migration, and focused return tests.

- [ ] Define eligible native/historical origin evidence: exact item, company, owner, issued allocation, remaining returnable quantity/value, serial identity where relevant, and approved destination.
- [ ] Test historical-origin return without creating a fabricated old MM issue; reject duplicate acceptance, over-return, wrong owner, unsupported serial, and closed period.
- [ ] Test linked reversal and dependent downstream movement. Where exact reversal is impossible, require an approved correction with separate effects; preserve the original posting.

**Acceptance:** P2 AT05 and AT06; E2E 08.

### PR 2D Inventory Adjustment and Write-off Adaptation — Developer B

**Files:** `InventoryProcessingService`, existing inventory/stock movement command paths, their tests, and approved account/owner context adapters.

- [ ] Test that a count/comparison document alone does not post an adjustment; only the approved decision changes stock and accounts.
- [ ] Test partial adjustment/write-off with exact allocation, reason, approval, period guard, and linked reversal. Reject cross-owner allocation.
- [ ] Demonstrate the ordinary receipt-to-transfer-to-issue-to-return-to-write-off chain against an independent reconciliation query.

**Acceptance:** Fills the ordinary write-off/inventory gap in P2's exit criteria; E2E 01.

### Milestone M1 Joint Backend Gate

- [ ] Both developers run focused tests and the full backend suite on the merged revision, then PostgreSQL tests against an upgraded V13-to-current schema.
- [ ] Execute P1 AT01-07 and the backend portions of P2 AT01-07 and applicable E2E 01, 02, 06-10 with stored command IDs, posting receipts, exact totals, and rejected-command evidence. Hand off Russian UI/action presentation checks to the frontend team.
- [ ] Verify additive migration on a representative PostgreSQL copy and compare complete keyed stock/account/cost/source fingerprints before and after.
- [ ] Obtain warehouse and accounting approval for the named pilot company/warehouses and prepare their backend enablement evidence. Actual release waits for the coordinated frontend and deployment gates.

## Later Backend Milestones and Developer Lanes

These requests are scheduled at the business-outcome level. Before each later milestone starts, the two developers write its exact command/interface and migration checklist against the merged code and approved D03/D05/D06/D09 examples. That avoids freezing unverified financial or production rules in this plan. Work may overlap only when no shared mutation or migration files are edited concurrently.

### Milestone M2 Phase 3 Materials in Use

**Gate:** M1 backend gate passes; D02 specifies account 014 and expense timing; pilot departments and responsible persons are approved.

| Merge request | Owner | Deliverable and test |
| --- | --- | --- |
| 3A Holdings and handover | B | Holding/origin model, dispatch and recipient acknowledgement with separate pending quantity. P3 AT01 and AT04; activation is idempotent and never replays the old source issue. |
| 3B Account and return effects | A | Shared-posting adapters for consumption, holding return, and write-off. P3 AT02 and AT05; no second expense after prior expensing. |
| 3C Responsibility and reconciliation | B | Accepted holder transfer, scope rules and independent holding statement. P3 AT03 and AT06; E2E 03. |

**Merge boundary:** 3A precedes 3B/3C; A owns any shared posting edit. Close only when each pilot holding reconciles by material, unit, owner, origin, holder and account/date dimensions.

### Milestone M3 Phase 4 Custody

**Gate:** M1 backend gate passes; D03 defines owner, custodian, agreement, nonowned value and settlement policy.

| Merge request | Owner | Deliverable and test |
| --- | --- | --- |
| 4A Owner-aware eligibility | A | Extend every enabled reservation, issue, transfer, receipt, inventory and cost allocation path to select the correct owner/category position. P4 AT01 and AT04; E2E 02. |
| 4B Agreements and source preview | B | Agreement and owner/custodian binding model, scoped source activation preview and counterparty reconciliation query. Unknown owner/holder stays blocked. |
| 4C Custody commands | A | Receipt, dispatch, return and ownership-transfer effects, with pending transport and VMI/consignment allocation linkage. P4 AT02, AT03 and AT05. |
| 4D Activation and statements | B | Idempotent approved custody activation and owner/custodian statements with separate owned/nonowned value bases. P4 AT06. |

**Merge boundary:** 4A precedes any operational custody command. Close only when generic MM commands cannot spend another party's stock and internal/external custody positions reconcile.

### Milestone M4 Phase 5 Installation Equipment and Assets

**Gate:** M1 backend gate passes; D05 defines active asset identity, source state, value, capitalization and commissioning authority.

| Merge request | Owner | Deliverable and test |
| --- | --- | --- |
| 5A Equipment/project handoff | B | Installation position, dispatch/acceptance, return and project balance using the shared origin and posting contract. P5 AT01 and AT02. |
| 5B Capitalization effects | A | Exact equipment/acquisition-to-native-asset allocations, partial capitalization and no double-counted stock/value. P5 AT03 and AT06. |
| 5C Source asset validation | B | Card/register state review, scoped binding, department/MOL location treatment, and historical/disposed exclusions. P5 AT04 and AT05; E2E 04. |

**Merge boundary:** 5A precedes 5B. An imported card cannot create an active asset through a read-only source view.

### Milestone M5 Phase 6 Industrial Operations

**Gate:** D06 assigns confirmed production event and final costing authority. External processing waits for M3; asset recovery waits for M4.

| Merge request | Owner | Deliverable and test |
| --- | --- | --- |
| 6A Production receipt adapter | A | Validated external event-to-shared-posting adapter, exact unit/value and quality disposition. P6 AT01 and AT06. |
| 6B Integration identity/reconciliation | B | Event ID/version/hash conflict handling, correction linkage, cutover duplicate boundary and independently reconstructed receipt totals. Repeated delivery posts once. |
| 6C Processing and transformation | A | Processor transit/consumption, cutting, assembly, disassembly, scrap and approved value allocation. P6 AT02-05. |
| 6D Industrial handoff tests | B | Sales/custody dispatch evidence without billing duplication, independent yield/value tests, partial processor reconciliation and E2E 05. P6 AT07. |

**Merge boundary:** Do not equate dispatch to a processor with consumption; the approved event or case command establishes actual consumption. A correction appends linked effects rather than editing a posted receipt.

### Milestone M6 Phase 7 Reports and Controlled Rollout

**Gate:** D09 approves report definitions, pilot scope and representative performance dataset. Reports for earlier phases ship with those phases; this milestone completes the combined family.

| Merge request | Owner | Deliverable and test |
| --- | --- | --- |
| 7A Report reconstruction | B | Material card, account/owner/group/warehouse filters, source/native coverage and independent opening-to-closing reconstruction. P7 AT01-04. |
| 7B Snapshot/export and performance | A | Identical scoped screen/totals/export snapshot, bounded background export jobs, PostgreSQL plans and concurrent-posting checks. P7 AT05 and AT07. |
| 7C Backend pilot evidence | Both | Run the backend/API portions of enabled phase scenarios with warehouse/accounting owners; preserve exact receipts, totals and role failures. Hand off Russian UI, CSV/Excel presentation and user-journey evidence for full P7 AT06 and E2E 10 acceptance. |

**Merge boundary:** A report is marked complete only when its underlying operation is enabled and its values match an independent reconstruction. Document any historical coverage cutoff and unresolved native classification.

## Daily Coordination and Merge Protocol

1. Fifteen-minute sync: report current branch, files touched, migration version, blocker, and intended merge order. Update the ownership table when an operation crosses a shared boundary.
2. Keep merge requests to one reviewable business outcome. A request is not finished when it only adds an enum, table, or endpoint; it must include the applicable effect, scope, and acceptance test.
3. Before review, author rebases or merges the latest integration branch, reruns focused and required PostgreSQL tests, and attaches exact before/after reconciliation evidence.
4. Reviewer checks mutation-path coverage, account/owner scope, idempotency, cost and source preservation, migration upgrade, and failure behavior. Merge only after both developers understand the resulting stock effect.
5. After merge, run the combined smoke scenarios immediately. If a regression appears, pause dependent merges and repair the owning contract rather than adding a second workaround path.

## Completion Boundary

This plan schedules backend code and verification. A backend milestone is ready for coordinated release only for its explicitly reviewed company/warehouse/operation scope, with approved rules, passing tests, unchanged source evidence, independent keyed reconciliation, and business acceptance of its backend effects. Full phase acceptance also requires the frontend, export presentation and deployment evidence specified in the source document. Unresolved historical positions remain visible and blocked for unsupported execution.
