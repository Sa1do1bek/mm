# Warehouse Backend Developer A Tasks and Main Merge Schedule

**Role:** Own the shared posting, stock, account, cost and allocation boundary. Own receipt, issue, return and reversal adapters. Later own cross-cutting owner eligibility, custody posting, production posting and report performance.

**Source:** [Two developer coordination plan](2026-10-14-two-developer-warehouse-backend.md) and `MM_Warehouse_Roadmap_Technical_Specification_2026-10-14.docx` version 1.0.

## Push and Merge Rules

- Continue on **`Sardor`** and push after each coherent test/code change and at least at the end of each workday. Keep its merge request in draft while work is incomplete.
- Do **not** push directly to `main`. “Push to main” below means merge an approved merge request into `main` after its gate passes.
- Keep `Sardor` current with reviewed dependencies from `origin/main`. When B's relevant work is ready, fetch its exact branch and base commit; if B has already merged to `main`, update from `origin/main`, otherwise review and integrate B's branch. Resolve conflicts and rerun affected tests. Local `main` is stale.
- Only merge a task that leaves `main` buildable, backward compatible and safe while its company/warehouse capability remains disabled. An additive migration must be safe for an existing deployment.
- Developer B reviews every change to the shared posting boundary. B must not be asked to merge a dependent activation or report change until the relevant A request is in `main`.
- If `main` automatically deploys, keep state-changing functionality disabled until the coordinated release gate. Merging code is not source activation or production enablement.

## Joint Gate G0 Before State-Changing Code

**Your task:** Write the backend posting contract and obtain B's technical review. Record the first-implementation D01 choice, fail-closed D02/D04/D07/D08 defaults, five engineering posting examples, physical/account/cost effect invariants, source cutover and the mutation-path register. The warehouse/accounting owners confirm financial effects and pilot rules before affected commands are enabled. Use a short documentation merge request reviewed by B.

**Merge to main:** First. No state-changing Phase 1 request merges before this contract. A read-only source-preview branch may be developed in parallel.

## Release 1 Phase 1

### A1 Posting Evidence and Rule Contract — PR 1A

**Start:** After G0. **Branch:** `Sardor`.

**Tasks**

- [x] Use V14 for additive scoped posting-batch/effect and rule-version storage under `src/main/resources/db/migration/`; leave installed V1-V13 untouched. B reported V13 as its latest migration and has not pushed new work from `mirzo`.
- [x] Add focused posting model/services under `src/main/java/com/enterprise/mm/warehouse/posting/`, including a transaction-scoped callback and required-key support in `IdempotencyService`.
- [x] Require an idempotency key on new state-changing commands. Identical committed retry returns the receipt before a stale-version error; same key with different payload conflicts.
- [x] Test scoped uniqueness, effect-type quantity/value rules, rollback, concurrent retry, and nullable location/bin/batch uniqueness on PostgreSQL.
- [x] Keep rule preview read-only; require approved and unambiguous effective rule before posting.
- [ ] Have B review the posting boundary and G0 contract. Run the combined build and PostgreSQL 16 test job before the merge decision.

**A1 ruling:** The journal records an operational receipt but does not emit an accounting event by itself. A1 has no enabled stock command, and D01 holds financial event delivery until the accounting/integration owners approve its contract. A2 adapters must call the existing audit and idempotent integration-outbox services inside the posting transaction when their event contract is enabled; they must not deliver events inline. If the approved contract requires a journal-level event instead, revise this boundary before A2 merges.

**Local verification, 14 October:** V13-to-V14 upgrade and eight PostgreSQL checks passed on an isolated PostgreSQL 17 instance; three existing stock concurrency tests passed on the test profile; the backend package built with tests skipped. PostgreSQL 16 CI and the full combined test suite remain merge gates.

**Merge to main:** After focused and PostgreSQL tests, V13-to-new migration test, B review, and a green combined build. This must merge before A2 or B's activation-apply request. B's read-only source preview may merge before or after A1 if it has no conflicting schema/shared-file changes.

### A2 Account-Aware Native Posting — PR 1C

**Start:** A1 is in `main`. **Branch:** `Sardor`.

**Tasks**

- [ ] Adapt `StockBalanceMutationService`, `InventoryCostingService`, `StockMovementAllocationWriter`, `StockMovementDocumentService`, `TransferPostingService` and `WarehouseReceiptPostingService` through the single posting context. Own shared-file edits for this request.
- [ ] Test one item/warehouse with two account positions: an issue consumes only the selected account, cost layer and eligible physical stock. Cover P1 AT01 and AT02.
- [ ] Test deterministic locks, expected version inside the transaction, two concurrent partial allocations, rollback of a failed account/cost effect, and unchanged legacy owned-stock behavior.
- [ ] Cover seven-decimal quantities, authoritative source value, positive zero-value stock and explicit final-allocation residual policy. Reject unsupported unit conversion instead of rounding.
- [ ] Audit receipt, issue, transfer, return, inventory, reservation, quarantine, opening and asset handoff callers. Adapt each enabled position type or block it explicitly until its later phase.

**Merge to main:** After A1, P1 AT01/02/05/06 and E2E 06/07 backend tests pass, B reviews all changed mutation paths, and independent before/after stock/account/cost totals agree. B's activation apply waits for this merge.

## Release 1 Phase 2

### A3 Receipt and Issue Context — PR 2A

**Start:** A2 and B's activation/report request are merged and the Phase 1 backend gate passes. **Branch:** `Sardor`.

**Tasks**

- [ ] Extend `WarehouseReceiptPostingService` and `IssueRequestService` with the approved origin, account, owner, quality and valuation context.
- [ ] Test a 100-unit receipt with 80 accepted, 10 damaged/held under approved policy and 10 outstanding; keep PO remainder and physical disposition explicit.
- [ ] Test that approval/closure does not equal issue; only posted quantity reduces stock/account value, and closing partial demand releases unused reservation.
- [ ] Cover standalone-origin validation, partial posting, permissions, closed period and identical retry.

**Merge to main:** After P2 AT01/04 backend tests pass and B reviews posting effects. B's grouped transfer request can progress in parallel but must update against the merged A3 before its own merge if shared behavior changed.

### A4 Historical-Origin Returns and Reversal — PR 2C

**Start:** A3 is in `main`; B's grouped-transfer request may run concurrently. **Branch:** `Sardor`.

**Tasks**

- [ ] Extend `MaterialReturnService` and return commands to accept a validated native or historical origin with item, company, owner, original allocation/value and remaining returnable quantity.
- [ ] Add origin-link storage in a reserved additive migration; do not fabricate a historical native issue.
- [ ] Test alternate approved return warehouse, condition-based availability/quarantine, over-return, duplicate acceptance, wrong owner, serial mismatch and closed-period rejection.
- [ ] Test linked reversal/correction where dependent movements exist. Preserve the original posting and source record.

**Merge to main:** After A3 and B3 are merged, P2 AT05/06 and E2E 08 backend tests pass, B approves the migration and origin/remainder logic, and the latest `main` is integrated. The M1 backend gate still waits for B's inventory/write-off request and joint scenarios.

## Later Milestones

| Milestone and request | Your tasks | Merge to main when |
| --- | --- | --- |
| M2 / 3B Materials-in-use effects | Adapt shared posting for consumption, return and write-off; honor account 014 expense timing. | B's 3A holding/handover model is merged; P3 AT02/05 prove no repeated expense. |
| M3 / 4A Owner-aware eligibility | Extend every enabled generic stock, reservation, transfer, count and cost path to select an owner/category position. | D03 approved; P4 AT01/04 and E2E 02 pass. This precedes custody execution. |
| M3 / 4C Custody commands | Post custody receipt, dispatch, return, owner transfer, consignment consumption and exceptions through the shared boundary. | 4A and B's 4B agreement/source model are merged; P4 AT02/03/05 pass. |
| M4 / 5B Capitalization effects | Link exact equipment/acquisition allocations to native assets; support partial conversion without duplicated value. | B's 5A equipment/project handoff is merged; P5 AT03/06 pass. |
| M5 / 6A Production receipt adapter | Validate confirmed event identity, scope, source bindings, unit, amount, quality and account context before shared posting. | D06 approved; P6 AT01/06 pass; duplicate delivery posts once. |
| M5 / 6C Processing and transformation | Post processor consumption, cutting, assembly, disassembly, scrap, by-product and approved losses with cost/yield evidence. | M3 custody and M4 asset recovery prerequisites are merged; B's 6B event identity is merged; P6 AT02-05 pass. |
| M6 / 7B Snapshot/export performance | Keep rows, totals and bounded exports on one scoped snapshot; measure PostgreSQL report plans with concurrent posting. | B's 7A report reconstruction is merged; P7 AT05/07 pass on representative data. |

For each later request, reserve its migration number just before implementation, push the feature branch regularly, and merge only after the other developer reviews its exact effects and the specified tests pass. Do not wait for all later milestones to finish before merging a safe completed request.

## Joint Main and Release Gates

- **After Phase 1:** A1, B1, A2 and B2 must all be merged and independently reconciled before Phase 2 state-changing adapters merge.
- **After Phase 2:** A3, B3, A4 and B4 must be merged. Run full backend tests, PostgreSQL upgrade/concurrency checks and the backend portions of P1/P2 and E2E scenarios on their combined `main` revision.
- **Production release:** Separate from merge. Warehouse/accounting owners approve pilot scope and posting rules; deployment verifies backup, migration and reconciliation. Frontend/user journeys are coordinated outside this backend-only plan.
