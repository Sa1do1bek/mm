# Warehouse Posting Contract for Backend Review

**Status:** Engineering contract for implementation review. The two developers may build and merge disabled backend capability against the decisions below. No new stock command or source activation is enabled by this document. Accounting, warehouse and integration owners must approve the affected rules, examples and pilot scope before activation.

**Baseline:** Backend `fbd3543c` from 13 October 2026; 1C warehouse source snapshot dated 8 October 2026 as described in `MM_Warehouse_Roadmap_Technical_Specification_2026-10-14.docx` version 1.0. This document records code observations from the backend checkout. It does not independently verify the private 1C exports or a live database.

## Purpose and Scope

One successful warehouse command must have one durable identity and an explainable set of physical, account, cost, origin, audit and integration effects. Existing operational stock remains authoritative until a separately approved source activation posts a proved missing delta. Reports may read immutable 1C evidence, but reading an imported row never executes it as an MM movement.

The backend contract covers tenant/company scope, source and native identity, eligible stock position, account and owner context, exact quantity/value, posting date, rule version, allocation, idempotency, concurrency, reversal and reconciliation. It applies to ordinary receipt, issue, transfer, return, inventory and write-off commands before later holdings, custody, equipment and production commands are enabled.

## Engineering Decisions for the First Implementation

These decisions let the backend work proceed without guessing financial entries or changing production stock. Developer B should review their technical consequences in this merge request. Any later business change to an authority or rule is a versioned decision and migration, not a silent reinterpretation of already posted evidence.

| Decision | First implementation choice | Activation boundary |
| --- | --- | --- |
| D01 Financial authority | 1C/FI owns the financial general ledger after cutover. MM owns an operational warehouse subledger: it records exact stock, account-position and cost allocation evidence and emits an idempotent accounting event through the outbox. MM does not create a financial GL entry for the same event. | No automatic financial event delivery for a pilot until accounting/integration owners confirm the event contract, per-book authority and duplicate boundary. |
| D02 Account rules and expense timing | Store explicit account and rule-version evidence for each new posting. Do not infer an expense account or an account 014 expense date from a source account code. A command requiring an undefined rule fails closed. The five examples below define quantity and allocation expectations, not unapproved debit/credit entries. | Do not enable an operation without approved account correspondence and expense timing for its scope. |
| D04 Units and residuals | Accept exact base-stock-unit quantities at up to seven decimal places. Reject a different transaction unit until its dimension, effective conversion factor and value residual policy are configured and approved. Preserve authoritative source amount rather than recomputing it from a rounded unit cost. | Conversion and split-value operations remain disabled until approved cases exist. |
| D07 Historical activation | Import 1C rows as immutable evidence and offer read-only classification/preview. Existing MM opening and subsequent native postings remain operationally authoritative. Never invoke the legacy direct stock-balance import route for the new source snapshot. | No historical activation without source manifest, cutover boundary, native-delta reconciliation and reviewed missing-delta receipt. |
| D08 Roles and acceptance | Reuse existing tenant/company/warehouse access checks for read-only evidence. New execution and approval actions are disabled by default; do not grant them by a generic administrator role alone. | Enable only after an action/approval matrix and recipient/MOL evidence are recorded for the pilot. |

## Current Mutation-Path Register

The following paths need a detailed call-by-call review in Phase 1. The table names entry points and direct balance writers found in the pinned code; it is not proof that every caller currently records every proposed effect.

| Path | Current code entry or writer | Phase 1 treatment |
| --- | --- | --- |
| Inbound integration stock-balance import | `InboundIntegrationSyncService.stockBalance` (direct `StockBalance` save) | Treat this as a separate mutation path. The current import assigns source quantities and value directly to an operational balance; source-preview imports must never call it. Scope and disable legacy execution for the new warehouse source until D07 reconciliation and activation are approved. |
| Opening and ordinary movement | `StockMovementService`; `StockMovementDocumentService`; `StockBalanceMutationService`; `InventoryCostingService`; `StockMovementAllocationWriter` | Preserve existing opening and movement identity. New postings carry account/owner/category context and allocations. |
| PO and warehouse receipt | `WarehouseReceiptPostingService`; `PurchaseOrderService`; `StockMovementService` | Connect accepted and held quantities to one posting context without changing PO partial-outcome semantics. |
| Managed transfer | `TransferPostingService`; `TransferDocumentService`; `StockMovementService` | Preserve source dispatch, transit, destination receipt and exception allocation. |
| Issue and return | `IssueRequestService`; `MaterialReturnService` (direct save after quarantine mutation); `StockMovementDocumentService` | Distinguish approved demand from posted issue; retain exact origin and returnable remainder. Verify return quarantine is one atomic continuation of the posted return. |
| Inventory adjustment | `InventoryProcessingService`; `StockMovementService` | A count or comparison statement alone does not post an adjustment. |
| Reservation | `MaterialReservationService` (direct save and flush); `ReservationStockMutationService`; `StockBalanceMutationService` | Reserve/release only eligible owner/category/account stock; verify both mutation implementations. |
| Incoming quality | `QualityInspectionService`; `QualityQuarantineLotService` | Direct available/quarantine balance changes retain owner and account context and never fabricate a second receipt. |
| Landed cost and valuation | `LandedCostAllocationService`; `StockValuationService`; `InventoryCostingService` | Direct `totalValue` changes need traceable value effects without changing physical quantity. |
| Nonmoving and asset handoff | `NonMovingStockCaseService`; asset acceptance/handover services | Check whether their stock and cost effects reach the same context before enabling new position types. |

`StockBalance` declares the logical key of tenant, company, material, warehouse, location, bin and batch. The installed V1 unique index uses PostgreSQL `NULLS NOT DISTINCT`, so the current null-key behavior must be preserved or explicitly migrated. The future owner/category partition cannot be introduced by changing only the entity key; all writers and readers above must either support it or reject that position type.

**Immediate integration risk:** `InboundIntegrationSyncService` currently writes imported balance fields into `StockBalance` as absolute values. A source row loaded through that route can replace operational totals without a posting receipt or delta reconciliation. The proposed 1C source-preview path must use immutable evidence storage and must not invoke this stock-balance import route. Phase 1 must test that importing the new source snapshot leaves existing operational balances unchanged.

## Proposed Shared Posting Rules

1. Authenticate and resolve tenant/company on the server. Validate the document, line, source position, material, warehouse, owner, account and actor scope before mutation.
2. Require a stable idempotency key for every new state-changing command. An identical committed retry returns its original receipt; a changed payload under the same key conflicts. A missing key fails validation.
3. Lock the document and affected position, cost-layer and origin-remainder rows in a deterministic order. Check expected document version and open posting period within the transaction. A successful identical replay is recognized before a stale-version rejection.
4. Select one effective approved posting rule and snapshot its version, book, account, owner, category, disposition, unit conversion, currency and valuation basis. If a rule is ambiguous or absent, do not post.
5. Allocate only eligible source stock and authoritative remaining cost/value. Plan all effects and check invariants before writing any effect.
6. Commit physical, account, cost, allocation, origin, audit and outbox evidence atomically. External event delivery happens after commit and cannot repost local stock.
7. Preserve a posted command. A reversal or correction appends a linked compensating posting with its own date, approval and effects.

**Physical invariant:** An issue or dispatch cannot spend more than eligible available quantity. A transfer's owner-specific quantity/value is explained across source, transit, destination and approved exceptions. Reclassification or ownership change cannot create a second physical receipt.

**Account/cost invariant:** For a selected book, currency and account rule, signed account effects and remaining cost/value reconcile with the posting receipt. A value-only effect has no executable stock quantity. Never add nonowned reference value to owned-asset totals.

**Origin invariant:** A source opening or historical return links to canonical source identity and original hash. An origin's consumed/returned remainder cannot become negative; an imported source row remains immutable.

**Projection invariant:** Operational availability, account balance, cost layers, transit, holdings and source evidence are separate views of related effects. Reports document the keys used to reconcile them and never add unlike units or both source and native representations of the same goods.

## Draft Example Effects for Business Review

These examples are engineering test inputs for stock and allocation behavior, not approved accounting instructions. Amounts and account correspondences are deliberately omitted where D02/D04 have not established them.

| Example | Given and command | Required backend result |
| --- | --- | --- |
| E1 Owned account issue | An approved issue selects 3 units from an eligible 1010 position with at least 3 available units. | Selected physical/account quantity and allocated cost decrease by 3 once. An approval without issue changes no on-hand quantity. Expense timing follows D02. |
| E2 Two source accounts | One item/warehouse has 4 units on account 1010 and 6 on another source account. Issue 3 explicitly from 1010. | The 1010 position decreases by 3. The other account retains 6. The physical projection loses only 3 units, not 6 or 13. |
| E3 Partial transfer | Dispatch 10 owned units; destination accepts 8 and records 2 pending or discrepant. | Source availability decreases by 10. Destination increases by 8. The other 2 remain in transit or a reviewed exception until resolved. The original dispatch allocation clears only once. |
| E4 Historical-origin return | A verified source issue has 5 issued units and 2 still returnable; accept a return of 2 into an approved warehouse. | One new native return is posted with the historical link and original value lineage. No old MM issue is fabricated; a retry adds nothing; a third unit is rejected. |
| E5 Value-only source position | A source account position has monetary value and zero physical quantity. | It remains reportable with original account, amount and currency. Activation does not create a unit, batch, bin or available stock. |

## Decision and Evidence Gates

| Decision | Owner | Required recorded evidence before affected command is enabled |
| --- | --- | --- |
| D01 Financial-book authority | Accounting and integration owners | Engineering choice above is 1C/FI for the financial GL and MM for the warehouse subledger. Confirm the per-book/event authority matrix and duplicate-posting boundary before financial event delivery. The source specification presents both choices and selects neither. |
| D02 Account rule and expense timing | Accounting and warehouse owners | Approved E1-E5 account/value examples, including material in use and account 014 when Phase 3 begins. |
| D04 Units and residuals | Warehouse and accounting owners | Allowed transaction/base unit pairs, factor effective dates, seven-decimal validation, authoritative amount scale and final allocation remainder. Until approved, commands keep explicit base-unit restrictions. |
| D07 Historical activation | Warehouse, accounting and data owners | Complete source snapshot manifest, cutover event boundary, existing native opening/native deltas, classification exceptions and approved missing-delta preview. |
| D08 Roles and acceptance | Warehouse, department and access owners | Action/approval matrix, preparer-approver separation, recipient/MOL evidence and pilot assignments. |

The code can store immutable posting evidence, fail closed on undefined rules and produce read-only previews while activation decisions are pending. It must not infer a policy by item name, source account prefix, warehouse tenure or an administrator's ability to call an endpoint.

## Reconciliation and Release Evidence

Before any pilot scope is enabled, record the exact company/source snapshot time, source content hashes, installed Flyway versions, native stock opening fingerprint, all native postings since the snapshot, and the approved activation preview. Reconstruct balances from opening plus signed posted effects by complete material, warehouse, owner, category, account, unit, currency and valuation keys. Keep unresolved, negative and value-only positions visible without unsupported execution.

The current checkout does not contain the private 1C export files or a live company-stock extract. The data owner must supply their manifest and the pilot company/warehouse scope before the source cutover and activation checks can be completed. No source position should be activated from the document's aggregate counts alone.
