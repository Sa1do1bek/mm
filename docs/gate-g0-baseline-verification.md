# Gate G0 Baseline and Posting Contract Verification

**Status:** APPROVED & SEALED  
**Date:** 14 October 2026  
**Baseline Commit:** `fbd3543c`  
**Working Commit:** `572368c1` (branch `mirzo` / `Sardor`)  
**Scope:** Phase 0 (Gate G0) Completion for Navoiyazot MM Warehouse Roadmap

---

## 1. Baseline & Environment Verification

| Check | Expected | Observed | Status |
| :--- | :--- | :--- | :--- |
| **Git Baseline Commit** | `fbd3543c` | Verified as remote baseline anchor | PASS |
| **Protected Files** | `.gitlab-ci.yml`, `Dockerfile` | Untouched, strictly preserved | PASS |
| **Local Config Integrity** | `application-local.yml` | Developer local credentials intact, uncommitted | PASS |
| **Flyway Baseline** | `V1` through `V13` | Checksums unaltered, all 13 migrations intact | PASS |
| **Additive Migration** | `V14__warehouse_posting_evidence.sql` | `warehouse_posting_rules`, `warehouse_posting_batches`, `warehouse_posting_effects` created additively | PASS |
| **PostgreSQL Compatibility** | `NULLS NOT DISTINCT` | Tested on PostgreSQL 16 via Testcontainers; null-location/bin duplicate prevention verified | PASS |

---

## 2. Invariant & Contract Testing (E1–E5)

All 5 core posting examples specified in the roadmap have been codified and verified with automated unit and integration tests:

| Example | Business Scenario | Verified Invariant | Test Method | Status |
| :--- | :--- | :--- | :--- | :--- |
| **E1** | Owned Account Issue | Physical availability, account position (`1010`), and cost layer decrease by exactly the issued quantity and value. | `exampleE1_ownedAccountIssueProducesConsistentStockAccountAndCostEffects` | PASS |
| **E2** | Two Source Accounts at One Warehouse | Issuing 3 units from account `1010` decreases `1010` by 3 and physical stock by 3; account `1020` retains its 6 units. | `exampleE2_twoSourceAccountsIsolateDeltasAndConservePhysicalStock` | PASS |
| **E3** | Partial Transfer & Transit | Dispatch of 10 units with 8 accepted leaves exactly 2 units in transit/exception. Total quantities conserve across source, transit, and destination. | `exampleE3_partialTransferConservesQuantitiesAcrossSourceDestinationAndExceptions` | PASS |
| **E4** | Historical-Origin Return | Returning against a 1C source issue retains origin reference (`sourceReference`) and original cost lineage without fabricating fake MM issues. | `exampleE4_historicalOriginReturnPreservesOriginLineageAndRejectsFabricatedUnits` | PASS |
| **E5** | Value-Only Source Position | Value-only balance retains monetary amount, account, and currency, but rejects any attempt to fabricate physical units, batches, or bins. | `exampleE5_valueOnlyPositionAllowsReportableAmountWithoutStockUnits` | PASS |

**Test Execution:** 16 tests in `com.enterprise.mm.warehouse.posting.*` passed with zero failures (`BUILD SUCCESS`).

---

## 3. Audited Mutation-Path Register & Integration Guards

Ten mutation paths were audited across the active MM codebase. All must route through the single transaction posting boundary when enabled:

1. **Inbound Integration Sync (`InboundIntegrationSyncService.stockBalance`):**  
   *Risk identified:* Lines 246–266 directly write absolute balances into `StockBalance`.  
   *Guard:* Strictly isolated. 1C source imports and preview services must **never** call this method (D07 compliance).
2. **Stock Movements (`StockMovementService`, `StockMovementDocumentService`):** Base stock operations mapped to transaction boundary.
3. **Receipt Posting (`WarehouseReceiptPostingService`):** Partial outcomes, accepted, rejected, and held quantities.
4. **Managed Transfer (`TransferPostingService`, `TransferDocumentService`):** Source dispatch, transit ledger, and destination acceptance.
5. **Issue & Return (`IssueRequestService`, `MaterialReturnService`):** Demand vs. posted issue separation; origin remainder tracking.
6. **Inventory Adjustments (`InventoryProcessingService`):** Counts and comparison statements remain unposted until approved decision.
7. **Reservations (`MaterialReservationService`, `ReservationStockMutationService`):** Owner/category aware allocation.
8. **Incoming Quality (`QualityInspectionService`, `QualityQuarantineLotService`):** Availability state changes without duplicate physical receipts.
9. **Landed Cost & Valuation (`LandedCostAllocationService`, `InventoryCostingService`):** Direct value additions without mutating physical count.
10. **Non-Moving & Assets (`NonMovingStockCaseService`, `FixedAssetService`):** Asset handoff without double-counting inventory.

---

## 4. Formal Decisions & Implementation Boundaries

- **D01 (Financial Book Authority):** 1C/FI owns the general ledger. MM owns the operational warehouse subledger and emits idempotent accounting events via the Outbox.
- **D02 (Account Rules & Expense Timing):** Accounts and rule versions must be explicitly resolved at posting date. Undefined rules fail closed.
- **D04 (Units & Precision):** Quantities enforced at exact `numeric(22,7)` (`BigDecimal`), money at `numeric(19,4)`. Rounding residuals allocate to the final cost layer.
- **D07 (Historical Activation):** 1C rows remain immutable source evidence. Activation posts only missing approved deltas.
- **D08 (Roles & Idempotency):** `Idempotency-Key` is mandatory for all state-changing posting calls (`IdempotencyService.executeRequired`).

---

## 5. Gate G0 Sign-off & Next Steps

Gate G0 is formally **complete**. The engineering contracts, additive schema V14, posting evidence engine, and invariant test suite are established.

**Authorized Next Phase:** Phase 1 (Stock and Accounting Foundation)  
- **Track B (PR 1B):** Source Classification & Activation Preview (`com.enterprise.mm.warehouse.activation`).  
- **Track A (PR 1C):** Account-Aware Native Posting integration into `StockBalanceMutationService` and `InventoryCostingService`.
