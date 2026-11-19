# Warehouse Backend Developer B Tasks and Main Merge Schedule

**Role:** Own source classification, activation, independent reconciliation and account reports. Own grouped transfers, transit, inventory adjustment and write-off adaptation. Later own holdings, custody evidence, installation/source assets, integration reconciliation and final report reconstruction.

**Source:** [Two developer coordination plan](2026-10-14-two-developer-warehouse-backend.md) and `MM_Warehouse_Roadmap_Technical_Specification_2026-10-14.docx` version 1.0.

## Push and Merge Rules

- Push commits to **your feature branch** after each coherent test/code change and at least at the end of each workday. Keep a merge request in draft while its dependency or checks are incomplete.
- Do **not** push directly to `main`. “Push to main” below means merge an approved merge request into `main` after its gate passes.
- Start from the latest `origin/main`; in this checkout local `main` is stale. Before final review, update from `origin/main`, resolve conflicts in your branch, and rerun affected tests.
- Do not create a separate stock writer to finish activation or a workflow. Apply stock only through A's reviewed shared posting contract.
- Reserve each new Flyway version with A before writing it. Never edit V1-V13 or use a version already assigned to another request.
- If `main` automatically deploys, keep activation and new commands disabled until coordinated release. A code merge is not permission to activate source stock or enable a company.

## Joint Gate G0 Before State-Changing Code

**Your task:** Co-author and review the posting contract with A and the warehouse/accounting owners. Supply source snapshot/hash evidence, historical identity rules, source-to-native cutover examples, and the independent reconciliation keys. Confirm D01, D02, D04, D07 and D08 decisions are recorded.

**Merge to main:** A merges the reviewed G0 documentation request first. You may begin B1's read-only tests in parallel, but do not merge a state-changing activation request before G0, A1 and A2.

## Release 1 Phase 1

### B1 Source Classification and Activation Preview — PR 1B

**Start:** After G0 contract is agreed. Can be developed alongside A1. **Branch:** `feat/warehouse-source-activation-preview`.

**Tasks**

- [ ] Build source-position classifications using existing V12 `warehouse_account_entries` and V13 source links: already represented, eligible missing, historical only, value only, negative/disputed and unresolved.
- [ ] Test canonical source identity, duplicate names/codes, zero references, warehouse-folder balances, unresolved destinations, value-only and negative positions.
- [ ] Calculate the preview by full account/material/warehouse/owner/unit/currency identity. Show snapshot, included source events, current native representation, native deltas, proposed missing delta, source hash and blocked reason.
- [ ] Define how corrected or backdated events cross the recorded cutover boundary. Do not infer a warehouse, bin, owner or asset identity from a name.
- [ ] Add scoped read/preview endpoints and exports if needed. Preview must never reserve, post or mutate imported content.

**Merge to main:** After read-only tests and source/permission review pass. This may merge before or after A1 because it does not touch the posting writer or take a competing Flyway number. If A1 merges first, update B1 from `origin/main` before final review.

### B2 Activation Apply, Account Report and Reconciliation — PR 1D

**Start:** B1, A1 and A2 are merged. **Branch:** `feat/warehouse-activation-reconciliation`.

**Tasks**

- [ ] Reserve the next free Flyway migration for activation approval/journal/receipt and any required account-position linkage.
- [ ] Apply only the approved missing delta through A's posting entry point. Use canonical source identity, preview hash, source content hash, company/cutover identity and a stable idempotency key.
- [ ] Test identical replay returns one receipt; changed source hash, payload, scope or cutover conflicts; partial failure leaves no stock/account/cost effect.
- [ ] Change `WarehouseAccountReportQuery` so **new proven** native effects use their resolved account. Leave older events lacking proof in an explicit unresolved account category.
- [ ] Reconstruct stock, account positions, cost layers, transit and source coverage independently from approved opening and signed posted effects. Compare every key, not just grand totals.
- [ ] Prove source row hashes remain unchanged and value-only/negative/disputed positions remain visible but non-executable.

**Merge to main:** After A2, P1 AT01/03/04/07 and E2E 09/10 backend tests pass, A reviews the activation call into shared posting, migration upgrade passes, and the enabled pilot keys have no unexplained difference.

## Release 1 Phase 2

### B3 Grouped Transfers and Transit — PR 2B

**Start:** Phase 1 backend gate passes. Can be developed alongside A3. **Branch:** `feat/warehouse-grouped-transfers`.

**Tasks**

- [ ] Reserve a migration for parent transfer group and stable parent-to-child line mapping. Keep one source/destination warehouse pair per existing managed child transfer.
- [ ] Test one request for three destinations produces three scoped child transfers and correct aggregate planned, approved, dispatched, received, outstanding and cancelled quantities.
- [ ] Use exact source dispatch allocations for each partial receipt. Keep loss, damage and outstanding quantity in transit or an approved exception until resolved.
- [ ] Enforce source dispatch and destination receipt authority independently; do not allow cross-company shortcut or guessed destination for unresolved source lines.
- [ ] Test canceling only an unexecuted remainder, and conservation of owner-specific quantity/value through source, transit and destination.

**Merge to main:** After A3. Update B3 against the merged `main`, rerun P2 AT02/03 and transfer PostgreSQL tests, then obtain A's review. A4 and B4 merge after B3.

### B4 Inventory Adjustment and Write-off Adaptation — PR 2D

**Start:** A2 and B2 are merged; can be developed alongside A4. **Branch:** `feat/warehouse-inventory-writeoff-context`.

**Tasks**

- [ ] Audit `InventoryProcessingService` and existing write-off/nonmoving execution paths for new account/owner/category allocation requirements.
- [ ] Test that a count or comparison statement alone does not post a stock adjustment. Only an approved decision can apply its exact difference.
- [ ] Test partial adjustment and write-off with cost/account allocation, owner eligibility, reason, approval, closed-period guard and linked reversal.
- [ ] Reject cross-owner stock selection, double posting and any unexplained difference after a failed command.
- [ ] Reconstruct an API-level receipt-to-transfer-to-issue-to-return-to-write-off chain using the independent reconciliation query.

**Merge to main:** After P2's inventory/write-off backend cases and E2E 01 pass, A reviews the shared posting adapter and the latest A4/B3 effects are included in the combined tests. Merging B4 completes the planned Phase 2 backend code, not the full release gate.

## Later Milestones

| Milestone and request | Your tasks | Merge to main when |
| --- | --- | --- |
| M2 / 3A Holdings and handover | Add holding/origin model, account 014 eligible activation, dispatch, acknowledgement and pending quantity; do not replay old issue. | D02 and pilot holder policy approved; P3 AT01/04 pass. This precedes A's 3B effects. |
| M2 / 3C Responsibility and reconciliation | Add accepted holder transfer, scope, consumption/return statement and unresolved-holding queue. | A's 3B is merged; P3 AT03/06 and E2E 03 pass. |
| M3 / 4B Agreements and source preview | Add owner/custodian agreement, binding, source preview and counterparty reconciliation. | D03 approved; A's 4A owner eligibility is merged before any executable custody request. |
| M3 / 4D Custody activation and statements | Add approved idempotent custody activation and separate owned/nonowned value statements. | A's 4C custody commands are merged; P4 AT06 and independent owner/custodian balances pass. |
| M4 / 5A Equipment/project handoff | Add installation positions, project dispatch/acceptance, return and remaining-balance query. | D05 approved; P5 AT01/02 pass. This precedes A's 5B capitalization. |
| M4 / 5C Source asset validation | Validate card plus dated state/location registers, asset binding, duplicate number and department/MOL custody mapping. | A's 5B is merged; P5 AT04/05 and E2E 04 pass. |
| M5 / 6B Event identity/reconciliation | Add external ID/version/hash deduplication, correction linkage, cutover duplicate boundary and independent production receipt totals. | A's 6A adapter is merged; repeated/conflicting events are covered by PostgreSQL tests. |
| M5 / 6D Industrial handoff tests | Test processor partial return, yield/value reconciliation, scrap/loss and warehouse dispatch evidence without duplicate billing. | A's 6C transformation commands are merged; P6 AT07 and E2E 05 pass. |
| M6 / 7A Report reconstruction | Add material card, account/owner/group/warehouse filters, opening-to-closing reconstruction and source coverage boundary. | D09 approved; P7 AT01-04 pass. This precedes A's 7B performance request. |
| M6 / 7C Backend pilot evidence | Join A for role-scoped API scenarios, exact receipts, report/export totals and business review. | A's 7B is merged; backend P7 AT06 and E2E 10 evidence is recorded. Full Russian UI acceptance remains a frontend gate. |

For each later request, push your own branch regularly and merge into `main` only after its prerequisite request, focused and PostgreSQL tests, exact reconciliation and A's review. A safe completed request merges before the rest of the milestone; production enablement waits for the milestone gate.

## Joint Main and Release Gates

- **After Phase 1:** A1, B1, A2 and B2 must be in `main`; their combined posting and activation effects must reconcile before Phase 2 state-changing adapters merge.
- **After Phase 2:** A3, B3, A4 and B4 must be in `main`. Both developers run full backend tests, PostgreSQL upgrade/concurrency tests and the backend portions of P1/P2 and E2E scenarios on the same revision.
- **Production release:** Separate from merge. Warehouse/accounting owners approve pilot scope, cutover and rules; deployment verifies backup, migrations and reconciliation. Frontend/user journeys are coordinated outside this backend-only plan.
