# Section 04 — Поставки и приёмка: design for coordinated review

Date: 2026-09-29. Status: **proposal, not approved for implementation**.

Scope: MM-04-01 through MM-04-07, without exclusions. Warehouse operations and inventory are the first delivery milestone, not a reduction of the full scope. This document is the only file written by this section during design preparation.

Research baseline: backend `40698b1`, frontend `a5777238`. Read PROGRAM.md, REQUIREMENTS.md, MM-SCOPE-ASSESSMENT.md and actual source. `mm-front/AGENTS.md` says to use Superpowers only when explicitly requested; the user explicitly requested using-superpowers, brainstorming and writing-plans. Those skills were read from `/Users/abdurahmoniy/.codex/superpowers/skills/`. The brainstorming approval gate remains in force. Clarifications and policy approval belong to the coordinator's combined review; no separate questions, implementation plan, commits, services, tests or provider messages were produced here. Writing-plans will structure the executable implementation plan **after** combined design approval.

## 1. Recommendation and alternatives

**Recommended — extend the existing receiving boundary with explicit receipt allocations and dispositions.** Preserve procurement, GoodsReceipt evidence, stock posting, quality, import and fixed-asset services. Extract focused receipt orchestration from PurchaseOrderService as it is modified; add typed allocation, exception, direct-recipient and equipment-completeness commands. Stock/quality/cost effects remain owned by their existing domains. This gives a usable warehouse milestone and keeps direct/site and equipment delivery on the same evidence contracts.

Alternative 2: add independent forms for direct delivery, excess and installation while leaving current posting unchanged. Lower initial effort, but would duplicate fulfillment, permit unacknowledged consumption, lose serial/owner history and continue schedule-allocation errors. Reject as the final architecture.

Alternative 3: replace receiving with a standalone inbound-logistics subsystem and move all equipment/import handling into it. Potentially cleaner long-term boundaries, but expensive migration and substantial regression risk to functioning supplier portal, fixed assets and invoice evidence. No demonstrated need for this replacement.

## 2. Evidence and requirement-by-requirement gaps

Paths below are repository-relative to the workspace root. Line numbers describe the inspected baseline; method names are stable anchors. These are static findings, not claims that browser or PostgreSQL acceptance has passed.

### MM-04-01 — Expected deliveries, schedules, ASNs, dates, contents, overdue

Existing:

- `mm-backend/src/main/java/com/enterprise/mm/entity/DeliverySchedule.java:22` stores planned/received quantity and amount, date, overdue flag, optional PO item and supplier commitment.
- `.../service/DeliveryScheduleService.java:73–110` controls overdue/received state; `:180–233` handles lifecycle-safe create/update/delete.
- `.../service/AdvanceShippingNoticeService.java:148–205` validates and submits ASN, confirms it and forbids manual mark-received: actual receiving must go through PO orchestration. Supplier portal also has `supplier/po/service/SupplierPoAsnService.java` and frontend `src/features/supplierPortal/purchaseOrders/SupplierPurchaseOrderAsnEditor.tsx`.
- `.../warehouse/goodsincome/controller/GoodsIncomeWorkspaceController.java:24–56` exposes receiving queue and PO context. Frontend `mm-front/src/components/PurchaseOrderReceivingPage.tsx:99–110,423–455` handles eligible ASN and next actions.

Missing/incomplete:

- `PurchaseOrderService.java:909–951`, `applyAsnReceiptToSupplierCommitmentSchedules`, distributes **the receipt's aggregate accepted quantity and amount** over ASN items in repository order. It does not consume accepted outcomes keyed by PO item. This can credit an unreceived material's schedule; mixed UOM totals are not a safe allocation basis.
- Manual schedules need explicit receipt allocation; the above code only finds schedules by supplier commitment. A header total does not establish delivery contents or partial schedule reconciliation.
- Overdue must use remaining expected quantity and the destination's business date, refresh after rescheduling/reversal and distinguish late supplier dispatch from pending recipient acceptance. Do not use an ASN header status alone as fulfillment evidence.

Proposed: line-level schedule/ASN/receipt allocation, versioned date/quantity amendment with reason and history, expected-inbound projection and explicit residual cancellation. A confirmed ASN reserves shipment capacity; receipt allocations consume only the corresponding ASN line. Supplier-originated promises remain section 03-owned.

### MM-04-02 — PO receipt, partial receipt, actual quantity

Existing:

- `.../controller/PurchaseOrderController.java:129–135` requires `Idempotency-Key`; `.../service/PurchaseOrderService.java:343–364` requires PO version and executes an idempotent command.
- `PurchaseOrderService.java:373–637` validates state, warehouse, material type, confirmed ASN, quantities and evidence, posts accepted stock and records physical/accepted/rejected/damaged quantities and partial PO status. Lines `:454–489` retain a legacy receivedQuantity interpretation; new clients send explicit acceptedQuantity.
- `.../warehouse/receiving/service/WarehouseReceiptPostingService.java:55–166` records immutable receipt header/line/evidence inside the caller's transaction. `:193–249` creates material units for accepted serials.
- Frontend `PurchaseOrderReceivingPage.tsx:530–579` submits version/key, destination, evidence, line outcomes and batch details; `src/features/goods-income/ReceiptOutcomePanel.tsx` and `ReceivingHistoryTable.tsx` expose result/history.

Missing/incomplete:

- Posted goods-receipt reversal orchestration was not found in this boundary. A generic movement reversal alone cannot consistently undo PO, ASN, schedule, serial, invoice evidence and costing.
- Separate arrival facts, business acceptance, quality availability and supplier obligation. Current `openQuantity` calls `FulfillmentQuantities.physicalReceiptRemaining` at `PurchaseOrderService.java:1480`; rejected/damaged delivery can exhaust physical PO headroom while replacement is still due.
- Explicit shipment-expected quantity is needed: current shortage calculation `:587–589` uses all remaining PO quantity, which can confuse an ordinary partial shipment with an actual shortage.
- Server commands must reject duplicate line IDs or consolidate them deterministically before calculating limits. Tests must prove concurrency across different idempotency keys, not just duplicate retry.

Proposed: existing `/purchase-orders/{id}/receive` remains compatible but delegates to one receipt command service; new receipt draft/validation/post and linked reversal commands use the same posting pipeline. Receipt header quantity totals remain display-only when UOM/materials differ.

### MM-04-03 — Shortage, excess, damage, nonconformance and approved action

Existing:

- `PurchaseOrderService.java:430,454–489,587–615` validates evidence and records shortage/damage/rejection. `:1604–1640` creates and quarantines incoming inspection for accepted stock with exceptions.
- `WarehouseReceiptPostingService.java:72–99` supports excess in recorded evidence, but `PurchaseOrderService.java:474–475` rejects processed quantity above PO remaining and `:604` always passes zero excess. A recording-level unit test accepting excess is not an operational excess workflow.
- Existing `.../service/QualityClaimService.java` and `QualityInspectionService.java` are the foundations for commercial claims and quality decisions.

Missing/incomplete:

- Record excess actually found without silently adding owned available inventory or increasing payable quantity.
- Route exception decisions to responsible approvers; record accepted deviation, supplier replacement, return, commercial short-close or corrected order as separate decisions with evidence.
- `PurchaseOrderService.java:581–583` passes a null inspection movement for multiple posted batches; `:1613–1617` returns without creating an inspection when that ID is null. Multi-batch exception receiving therefore needs a batch-level quality orchestration test and fix.
- Capture rejected/damaged goods physically retained on site, with owner/holder/status and serial identity, rather than making them disappear from custody records.

Proposed: receipt exception cases and quantity-specific decisions, linked to section 08 quality/returns and section 03 claims/PO amendments. A receipt can be posted with an open case; quality/ownership restrictions still prevent consumption. Shortage is missing physical goods, not stock to write off.

### MM-04-04 — Batches/serials, provenance, manufacture/expiry, quality

Existing:

- `PurchaseOrderService.java:802–835` validates batch policies, accepted batch sums and serial counts; `:838–878` builds batch evidence with manufacture/expiry.
- `.../entity/GoodsReceiptBatch.java:16–35` persists immutable dates and outcome quantities. `.../entity/StockBatch.java:22–52` has operational batch/date fields.
- `WarehouseReceiptPostingService.java:193–249` records serial receipt evidence and accepted MaterialUnit origin; validation later in the same class rejects mismatches/duplicates.
- Frontend `src/features/goods-income/ReceiptLinesEditor.tsx:23–35,230` models batch/serial details; `PurchaseOrderReceivingPage.tsx:554–563` sends dates and units.

Missing/incomplete:

- `.../procurement/port/ProcurementStockPostingPort.java:11–24` carries batch number but **no manufacture date, expiry, manufacturer, origin or certificate**. `.../service/StockMovementProcurementPostingAdapter.java:16–33` forwards that restricted contract. Dates saved in receipt evidence are not evidence of operational batch propagation; StockMovementService has no setters for manufacturedOn/expiresOn from this receipt path.
- Manufacturer/origin/certificate snapshots must be linked to the actual batch, with controlled conflict resolution when an existing batch number has different provenance. Supplier identity alone is insufficient.
- Quality must block each affected batch/unit, including multi-batch and serial receipts. Receipt-unit ACCEPTED must not imply freely issuable when its batch is held.
- Expired/future-manufacture/date-order validation and minimum remaining shelf life must consume section 01 policy; section 08 owns subsequent expiry/retest.

Proposed: immutable provenance snapshot and shared operational batch identity; per-batch/unit dispositions, harmonized expiry fields, certificate attachment links and lineage to supplier/PO/ASN/receipt/movement/quality decision.

### MM-04-05 — Direct delivery to department, project or site with recipient confirmation

Existing boundary:

- `PurchaseOrderService.java:394–403` requires a warehouse; `WarehouseReceiptPostingService.java:64–66` requires one warehouse for all lines. `PurchaseOrderReceivingPage.tsx:532–536` has the same assumption.
- `.../service/PurchaseOrderReceivingModeService.java:42–79` routes goods, fixed assets and services, with mixed legacy document blocking. It does not represent a direct-recipient goods delivery.
- Fixed-asset `AssetHandoverService.java:233,271` has dispatch/acknowledge, but is not ordinary material custody.

Gap: no dedicated ordinary-material delivery destination, recipient acceptance, partial confirmation/refusal, beneficiary stock/custody or consumption treatment was found in this receiving flow.

Proposed: a goods destination kind independent of receiving category: WAREHOUSE, DEPARTMENT, PROJECT_SITE. Site/department locations are valid physical holders under section 01/05/06 contracts, not fabricated central warehouse movements. Direct delivery uses a recipient-confirmation document, with authenticated recipient role, confirmed/rejected/pending quantities and evidence. Section 07 owns the resulting custody and later consumption; section 09 owns cost treatment. Project and department assignment are validated against the order/demand lineage.

### MM-04-06 — Import dossier, transport/customs evidence, landed costs

Existing:

- `.../entity/ImportDelivery.java` stores PO/ASN/supplier, Incoterm, origin/destination, warehouse, freight dates, declaration link and cost totals.
- `.../service/ImportDeliveryService.java:93–173` supports booking/transit/customs/delivery/close; customs release checks a released declaration at `:126–131`.
- `.../service/ImportFreightBookingService.java:126–140` and `ImportInsurancePolicyService.java:135` generate landed-cost components. `.../service/LandedCostAllocationService.java:81–150` posts finance-authorized costs to locked stock balances and costing layers; `:195–210` supports allocation methods/currency/rate.
- `mm-front/src/modules.tsx:5679–6120` contains import, freight, insurance, customs and cost component fields/actions; `:8891` contains allocation configuration. This is functioning domain scaffolding, not proof of a complete dossier.

Missing/incomplete:

- Document checklist/version/evidence validation per shipment and declaration line, with allocation across partial receipts and eligible batch/unit sources.
- `ImportDeliveryService.deliver` (`:141–152`) changes logistics status without checking receipt evidence and permits IN_TRANSIT; `close` (`:155–163`) requires only DELIVERED. A logistics delivery milestone must not masquerade as stock receipt or customs release.
- `recalculateEntity` (`:188–211`) sums included component amounts and released declaration totals; this code has no explicit currency conversion/status filter/source-deduplication. Dossier totals need a canonical cost-source ledger and base-currency reconciliation, jointly with section 09.
- Current landed-cost post requires an existing positive stock balance (`LandedCostAllocationService.java:104–127`). Direct-consumed goods, already-issued inventory and equipment need destination-aware cost adjustments, not redistribution onto unrelated remaining stock.

Proposed: retain existing import objects, add dossier document links, clearance coverage, receipt allocations and closure blockers; section 09 owns cost capitalization/correction with immutable source allocation and no duplicate use of a freight/customs charge.

### MM-04-07 — Equipment receipt/completeness, storage, installation/asset handoff

Existing:

- `.../service/AssetAcceptanceService.java:132–242,276–436` creates unit-level acceptance, resolves quality, requires approval and posts accepted units exactly once; `:394` locks the PO.
- `:890–905` creates FixedAsset in IN_WAREHOUSE/Warehouse custody; `:440–489,1017–1029` restricts reversal after financial/custody/commissioning dependencies.
- `.../entity/AssetAcceptanceLine.java` and `AssetAcceptanceUnit.java` represent quantity, quality, serial and posted asset identity. No expected kit-component checklist is present in those entities.
- `.../service/AssetHandoverService.java:233–276` provides dispatch/acknowledge; `FixedAssetService.java:281–286` commissioning exists.
- Frontend `src/features/fixed-assets/FixedAssetReceivingWorkspace.tsx:239–249,453,536` has unit quality and details; `AssetCommissioningWorkspace.tsx` and `AssetHandoverWorkspace.tsx` provide related workflows.

Missing/incomplete: expected-vs-received component/accessory/document completeness by equipment unit, partial component arrivals against the same unit, named storage responsibility and an explicit installation handoff with receiving party confirmation were not established by those models. Generic employee asset handover is not proof of installation acceptance.

Proposed: preserve FIXED_ASSET versus TMB routing; add equipment completeness snapshots and unit/component custody, extend the existing handover aggregate with purpose INSTALLATION and project/site/work-order recipient. Installed/commissioned confirmation remains a separate authorized command. TMB equipment remains material/custody inventory until an explicitly approved asset conversion; no automatic duplicate asset creation. Finance owns depreciation/statutory recognition.

## 3. Proposed contracts, data and workflows

### 3.1 Receipt quantities and allocation

Use decimal base quantities and immutable document UOM/conversion snapshots from section 01. Each receipt line belongs to one PO item. Split allocation rows identify ASN item, delivery schedule/commitment and expected shipment quantity. One receipt may include several PO lines in one destination; heterogeneous UOM must never be added to determine fulfillment.

For each line define physical arrival as `accepted + qualityHold + rejected + damaged + excessPending`. These are mutually exclusive intake buckets. ExcessPending is unapproved quantity beyond the allocation/order allowance; a later approved decision can transfer it to accepted or return status. A damaged unit subsequently accepted with deviation changes disposition through an event; it is not counted twice. Shortage is `max(expectedThisArrival - physicalArrival, 0)` only when a shipment-complete observation is recorded. An ordinary partial receipt leaves the shipment remainder open without a shortage assertion.

Keep separate measures:

- Delivered/observed quantity, net of physical return/reversal.
- Commercially accepted quantity, net of acceptance reversal/return correction.
- Quality-held quantity and freely usable quantity.
- Remaining supplier obligation: ordered quantity plus approved commercial quantity amendments, minus accepted fulfillment and approved short-close. Replacement authorization expands physical receiving headroom for the previously rejected quantity; it does **not** add the same quantity again to the commercial obligation. Physical arrival alone never extinguishes that obligation.

Add `ReceiptAllocation` linking immutable receipt line/batch to ASN item, schedule and base quantities. Use it for date performance, expected-inbound netting and reversals. Add an explicit replacement authorization linked to a rejected receipt/claim; section 03 controls its unused physical headroom so section 04 cannot create unlimited overreceipts by repeatedly rejecting goods. Example: PO10, arrived10 including rejected2, commercially accepted8 means obligation2; authorized replacement2 permits a second arrival2, leading to total observed12 and accepted10, obligation0. Each authorization can be used once up to its quantity.

### 3.2 Document states and exceptions

New editable receiving aggregate: DRAFT → READY → POSTED or POSTED_WITH_EXCEPTIONS; DRAFT/READY may CANCEL. Posted evidence is immutable. Reversal/decision progress is derived from child documents; do not edit old observed quantities. Posting may create accepted stock, held stock or non-owned custody in one transaction, plus evidence/allocations/outbox.

`ReceiptExceptionCase`: source receipt/allocation/batch/unit, type SHORTAGE/EXCESS/DAMAGE/QUALITY/DOCUMENT/COMPLETENESS, observed quantity, evidence, proposed action, approver, version and decision ledger. Decisions: ACCEPT_DEVIATION, ACCEPT_EXCESS_AFTER_PO_AMENDMENT, REQUIRE_REPLACEMENT, RETURN_TO_SUPPLIER, SHORT_CLOSE, RELEASE_AFTER_QUALITY. Partial decisions allowed; sum of effective decisions cannot exceed the case's unresolved quantity. Decision approval and physical return are distinct facts. Section 08 executes supplier returns; section 03 owns claims and order changes.

Quality approval cannot overrule ownership or PO commercial limits. Procurement approval cannot release failed quality. Evidence attachments must belong to the same tenant/company and source context; their content/version/hash is retained by section 10.

### 3.3 Batch and serial provenance

Create/resolve batch identity through section 01/05 services before posting, supplying supplier, manufacturer, manufacturer batch, origin country, manufacture/expiry, certificate/version and PO/ASN receipt allocation. Reuse is permitted only for matching identity/provenance; a conflicting date/origin creates an actionable conflict, not last-write-wins. Retain imported historic unknowns as unknown; do not manufacture dates during migration.

The posting port accepts base quantity, complete stock key (owner/holder/status/earmark), batch identity, provenance snapshot and per-unit dispositions. It returns allocation/movement/cost-layer IDs for **every** batch, not merely the first. Incoming quality cases reference these allocations; serial statuses follow batch hold/release rules. Rejected/damaged retained serials get custody identity even when they are not owned available MaterialUnits.

### 3.4 Direct delivery

`DirectDeliveryConfirmation`: receipt/order/ASN, destination, recipient or authorized recipient group, expected quantities, submitted evidence, cumulative confirmed/refused quantities and version. States AWAITING_CONFIRMATION → PARTIALLY_CONFIRMED → CONFIRMED or RESOLVED_WITH_EXCEPTIONS. Recipient-specific authority is required at command execution, not inferred from a selectable person ID.

Default sequence: arrival records holder/site and PENDING_RECIPIENT restricted custody; recipient confirms some/all; commercial acceptance, project/department custody, cost evidence and PO/schedule fulfillment advance for the confirmed quantities. Pending and refused quantities stay traceable and unavailable. If Incoterm/title policy transfers ownership before confirmation, book owned restricted stock at that holder with provisional cost; do not book recipient consumption or claim acceptance merely because ownership transferred. Non-owned custody is excluded from own inventory value.

Confirmation creates section 07 custody evidence exactly once. Default is delivery-to-custody, with subsequent consumption report. A separately authorized direct-consumption operation may combine acceptance and consumption atomically only when the PO purpose, quantity, recipient and cost object are approved; it retains both events. Partial refusal creates an exception/return obligation. Recipient changes require cancel/reissue of unconfirmed scope or an approved custody handover for confirmed scope.

### 3.5 Import and equipment

Import dossier links each transport/customs/commercial certificate to shipment scope, document number/date/issuer, attachment version and verification status. Customs release records declaration-line covered quantity; warehouse/site arrival under customs hold remains recordable but unavailable. Closure requires receipt/exception reconciliation, required evidence and either finalized costs or a finance-approved accrued-cost remainder with due date. Logistics milestones do not themselves post stock.

Cost source identity is unique by tenant/company/source-document/source-line/type. Components retain source currency, approved exchange-rate snapshot, base amount, recoverable-tax treatment and distribution method. Section 09 allocates to receipt layers/consumption/project cost or equipment acquisition; section 04 displays allocation status and unresolved expenses. Customs totals and generated components must not both count the same duty.

Equipment `EquipmentCompletenessLine` belongs to an acceptance unit, snapshots expected component/material/serial-or-quantity/document from approved specification, and records received/accepted/missing/damaged counts and linked receipt/case. Subsequent component arrival attaches to the existing unit and does not create a second asset. Storage retains location, responsible custodian and condition. Installation handoff includes asset/material unit and component allocations, project/site/installation order, responsible sender/recipient, dispatch and partial recipient acknowledgement, refusal and return links. Commissioning requires completeness or a specific approved waiver; installation acceptance does not initiate depreciation automatically.

## 4. API and UI shape

Keep current endpoints and route existing clients through the shared invariant checks. Proposed additive endpoints (names to be frozen by coordinator, not implemented here):

| API | Behavior |
|---|---|
| `GET /api/workspaces/goods-income/expected-deliveries` | Line-level expected/open/late by date, destination, supplier, PO, ASN and material; stable paging and drilldown IDs. |
| `POST /api/goods-receipts`, `PUT /api/goods-receipts/{id}` | Save versioned draft with typed destination, expected allocations, outcomes and provenance. |
| `POST /api/goods-receipts/{id}/validate`, `/post` | Return blockers/allowed actions, then atomically post explicit line outcomes. Existing PO receive remains a command adapter. |
| `GET /api/goods-receipts/{id}` | Evidence, allocations, source history, dispositions, quality/cost/recipient status and reversal availability. |
| `POST /api/goods-receipts/{id}/reversals` | Quantity-specific linked reversal request; approval/post governed by section 10/09 and dependencies. |
| `POST /api/receipt-exceptions/{id}/decisions` | Versioned proposal; shared approval engine approves applicable quantity/action. Physical execution uses owning domain command. |
| `POST /api/direct-delivery-confirmations/{id}/confirm` | Authenticated recipient's partial accepted/refused outcomes, proof and version/key. |
| `POST /api/import-deliveries/{id}/dossier-documents`, `GET .../closure-readiness` | Link verified evidence and expose clearance/receipt/cost blockers. |
| `POST /api/asset-acceptances/{id}/completeness-decisions` | Per-unit expected/observed component decisions with approvals. |
| Existing asset handover commands + purpose INSTALLATION | Extend dispatch/acknowledge with component scope; do not build an unrelated second asset transfer system. |

All mutating commands require Idempotency-Key and expectedVersion; same key/same payload returns recorded result, different payload conflicts. Responses include effective quantities, posting/evidence IDs, business date, version and blockers. No UI-only validation.

Frontend extends the existing goods-income workspace with tabs Expected / Receive / Exceptions / History. Receiving steps: source and destination → actual line/batch/unit outcomes → evidence and exception decisions → posting preview/result. Show expected-this-shipment, physical, accepted, held, rejected/damaged, excess and PO-open separately; avoid the current internal draft name `physicalReceivedQuantity` meaning accepted input. Provide save/resume draft, stale-version reload, retained input after validation failure and retry using the same key after network uncertainty. Receive-all remains an explicit user action.

Direct delivery gets recipient inbox and partial confirmation, visible pending/refused balances and project/department links. Import dossier consolidates existing records, missing-document checklist and receipt-cost reconciliation. Equipment acceptance shows kit completeness and storage/installation handoff. Reuse React/Ant Design, existing query invalidation and RU/UZ/EN copy; no generic JSON forms for business outcomes. Auditors have read-only drilldowns. Location/owner restrictions affect both options and server authorization.

## 5. Permissions, audit, reversals and concurrency

- **Warehouse receipt:** default warehouse receiver roles plus company/warehouse/location scope. Procurement can prepare schedules/ASN and propose resolution, but cannot physically receive without receiver authority. Current mismatch to resolve: SecurityConfig.java:239 permits warehouse/MM roles while GoodsIncomeReceiveActionPolicy.java:24–32 also advertises PROCUREMENT_MANAGER/SPECIALIST. Backend routes, domain policy and UI allowedActions must share one matrix.
- **Direct receipt:** delegated destination recipient can confirm only their assignment; warehouse role is not automatically a recipient. Quality decisions require quality role. Excess/order amendments require procurement authority, financial impact requires finance approval. Equipment acceptance/installation use the existing asset authorization model plus recipient assignment.
- **Separation of duties:** creator/receiver cannot approve their own discrepancy financial disposition or reversal. Normal conforming receipt needs no extra approval. Emergency exceptions are explicit, reasoned, time-limited and audited, not ADMIN silent bypass.
- **Scope:** derive tenant/company from authenticated context. Resolve each referenced PO/ASN/schedule/material/attachment/location/recipient within scope. Foreign IDs return no data and cause no writes. Recheck permissions on idempotent replay; stored responses cannot bypass current scope.
- **Audit:** record actor, timestamp, operation date/business timezone, reason, request correlation, version/key, before/after draft or immutable command event, approval, evidence hash and affected allocation IDs. Outbox creation is atomic with posting; provider failure does not lose receipt or create it twice. No provider connectivity claim without actual confirmation.
- **Partial/reversal:** allow reversal of an identified unconsumed quantity/allocation only; reverse receipt stock/custody/cost/PO/ASN/schedule/serial effects together. Pending quality quantities require matching hold reversal. Confirmed recipient custody, invoicing, consumed/transferred units, landed-cost adjustments and closed periods are explicit dependencies. Either reverse downstream documents first or issue an approved current-period correction referencing the original; never mutate historic evidence or create negative stock. Physical supplier return after valid acceptance is a return workflow, not falsification of the arrival fact.
- **Concurrency:** lock PO items and ASN/schedule allocations in deterministic ID order, then destination stock keys and serial identities. Require document versions, transaction-level unique idempotency key/payload hash, and database uniqueness for active serial identity/source allocations. Recheck capacity after locks; two different keys must not overreceive the same entitlement. Receipt effects and outbox are one transaction. Lock/disposition conflicts return actionable 409; validation returns structured blockers; no partial success within one post.
- **Dates/periods:** operational date, physical arrival timestamp and posted timestamp are distinct. Section 09 period guard covers receipt, reversal and cost post. Default no backdated posting into a closed period; current-period correction links the original date.

## 6. Cross-section contracts and ownership

| Section | Required contract / section 04 output |
|---|---|
| 01 Master data | Base/document UOM conversion snapshot, batch/serial/expiry/quality policy, valid site/holder/location/custodian, material versus equipment classification. 04 returns immutable captured provenance. |
| 02 Demand | Consume expected-inbound by PO item/destination/date/base UOM without double-counting ASN and PO; 04 supplies accepted/pending/reversed allocations linked to demand. |
| 03 Procurement | PO amendments, replacement entitlement, supplier commitment dates, confirmed ASN, claims and short-close decision. 04 supplies actual physical/accepted/exception facts; does not edit commercial terms. |
| 05 Stock | Sole writer of stock mutations; full stock key, allocation IDs, holder/status and atomic reversal. 04 supplies receipt intent/provenance; agrees lock order before implementation. |
| 06 Special stock | Independent owner/holder/earmark/title policy for rejected retained goods, excess, direct-site and consignment receipts. Do not default supplier-owned material to own available stock. |
| 07 Custody/consumption | Direct-recipient confirmation to custody and subsequent consumption; installation/project recipient responsibility. 04 supplies receipt/serial quantities and source cost lineage. |
| 08 Quality/returns | Per-allocation quarantine/release, expiry, incoming inspection, supplier return execution and traceability. 04 owns arrival/exception facts, 08 owns disposition execution. |
| 09 Cost/invoice | Receipt-line eligible evidence, provisional/held/pending-recipient eligibility, landed-cost allocation to remaining/consumed/asset value, period locks, financial reversal. Default invoice-ready excludes unresolved quality or recipient hold. |
| 10 Documents | Shared approval/permissions, numbering, attachment/version/signature, idempotency/replay, audit/outbox, integration status and corrections. |
| 11 Reports | Expected/late deliveries, acceptance lead time, shortages/excess/damage, direct-confirmation aging, batch provenance, import cost reconciliation and equipment completeness, all reconciled to allocation IDs. |

Shared files (stock key, costing, security, frontend registries and migrations) require one coordinator-assigned writer. Section 04 must not independently alter section 05/06 stock schema or section 09 valuation policy. Dependencies postpone implementation slices; none remove requirements from scope.

## 7. Default policies proposed for the single user review

1. Zero automatic overdelivery tolerance. Record all physical excess in restricted custody; accept commercially only after authorized PO amendment/explicit contractual tolerance decision. No automatic payable quantity.
2. Short shipment does not auto-close PO or create a shortage for every unreceived ordered unit. Explicit shipment-complete shortage observation and approved replacement/short-close decision are required.
3. Quality policy decides inspection for ordinary receipts; damage/nonconformance/expired goods always hold affected scope. One bad batch does not hold an unrelated good batch unless a supplier/lot-wide decision says so. Expired goods may be physically recorded under hold, never released without authorized section 08 disposition.
4. Confirmed ASN remains the normal TMB receipt prerequisite; emergency unscheduled arrival may be saved/observed under hold, but cannot silently bypass source authorization. Create/approve the missing ASN/source before commercial acceptance. Preserve current fixed-asset ASN policy until harmonized explicitly.
5. Direct delivery defaults to receiver-confirmed custody, not immediate expense. Title-before-acceptance uses restricted owned stock and provisional valuation; financial policy is section 09-owned.
6. Partial recipient acceptance is allowed. Named recipient substitution requires delegation history. No self-approval of financial discrepancy/reversal.
7. Import physical arrival is recordable before customs release, but use/issue is blocked for unreleased declared scope. Closing with pending costs requires an approved accrual and follow-up obligation. Recoverable tax is not capitalized by default; section 09 must approve applicable accounting policy.
8. Equipment missing mandatory components may be stored/received on hold. Installation/commissioning is blocked until complete or explicit approved waiver. Accounting asset record and physical installation are distinct milestones.
9. Reversals are linked, quantity-specific and dependency-aware; closed-period history stays immutable. No deletion of posted receipts, certificates or acknowledgements.

These are proposed operational defaults, not decisions already obtained from the user. Coordinator should call out title transfer, direct consumption, overdelivery, customs/quality holds and equipment waiver policy in combined approval.

## 8. Likely file map and warehouse-first sequence

Backend base path: `mm-backend/src/main/java/com/enterprise/mm/`.

Modify existing receiving files: `service/PurchaseOrderService.java`, `payload/BusinessActionDtos.java`, `controller/PurchaseOrderController.java`, `procurement/port/ProcurementStockPostingPort.java`, `service/StockMovementProcurementPostingAdapter.java`, `warehouse/receiving/service/WarehouseReceiptPostingService.java`, its `model/Receipt*PostingDraft.java` records, `warehouse/goodsincome/{service/GoodsIncomeWorkspaceService.java,service/GoodsIncomeReceiveActionPolicy.java,payload/GoodsIncomeDtos.java,repository/GoodsIncomeQueryRepository.java,controller/GoodsIncomeWorkspaceController.java}` and receipt entities/repositories.

Proposed focused additions under `warehouse/receiving/`: `service/GoodsReceiptCommandService.java`, `service/ReceiptAllocationService.java`, `service/ReceiptReversalService.java`, `service/ReceiptExceptionService.java`, `service/DirectDeliveryConfirmationService.java`, `controller/GoodsReceiptController.java`, `controller/ReceiptExceptionController.java`, `controller/DirectDeliveryConfirmationController.java`, `payload/GoodsReceiptCommands.java`. Add entities/repositories for `GoodsReceiptDraft`, `GoodsReceiptDraftLine`, `ReceiptAllocation`, `ReceiptExceptionCase`, `ReceiptExceptionDecision`, `DirectDeliveryConfirmation`, `DirectDeliveryConfirmationLine`, `ReceiptReversal` and `ReceiptReversalLine`; reuse existing final GoodsReceipt evidence rather than overwriting it.

Import/equipment changes: existing `service/ImportDeliveryService.java`, `ImportFreightBookingService.java`, `ImportInsurancePolicyService.java`, `CustomsDeclarationService.java`, `LandedCostComponentService.java`; new `service/ImportDossierService.java`, `entity/ImportDossierDocument.java`, `entity/ImportReceiptAllocation.java`; extend `service/AssetAcceptanceService.java`, `service/AssetHandoverService.java`, `payload/AssetAcceptance*Request.java`, `entity/AssetAcceptanceUnit.java`; add `entity/EquipmentCompletenessLine.java` and `service/EquipmentCompletenessService.java`. Section 09 owns changes to `LandedCostAllocationService.java`/`InventoryCostingService.java`; section 05 owns stock service changes; section 10 owns shared `security/SecurityConfig.java`/approval registrations.

Frontend: modify `mm-front/src/components/PurchaseOrderReceivingPage.tsx`, `src/features/goods-income/{ReceiptLinesEditor.tsx,ReceivingHistoryTable.tsx,ReceiptOutcomePanel.tsx,GoodsIncomeContextTabs.tsx,api.ts,types.ts,copy.ts,receivingContract.ts}`. Add `ExpectedDeliveriesTable.tsx`, `ReceiptExceptionPanel.tsx`, `DirectDeliveryConfirmationWorkspace.tsx` in that feature. Add `src/features/import-deliveries/ImportDossierWorkspace.tsx`; extend `src/features/fixed-assets/{FixedAssetReceivingWorkspace.tsx,AssetHandoverWorkspace.tsx,AssetCommissioningWorkspace.tsx,api.ts,types.ts,copy.ts}`. Coordinate route/navigation/module registry changes with one writer; `src/modules.tsx` contains the existing generic import forms and must not have parallel edits.

Migrations: additive SQL in `mm-backend/src/main/resources/db/migration/`, exact version allocated by coordinator after all section schemas are reconciled. Existing checkout exposes `V1__baseline_schema.sql`; do not rewrite this baseline. Backfill receipt allocations only where evidence proves the mapping; ambiguous legacy header schedules/provenance remain explicitly unresolved. Add tenant/company/source FKs, nonnegative/check constraints and unique indexes after validating legacy rows. Test fresh PostgreSQL and upgrade from populated baseline.

Delivery slices after approval, before the detailed writing-plans plan:

1. Freeze UOM/owner/stock/quality/period/authorization contracts with sections 01/05/06/08/09/10. Agree receipt allocation quantities and migration ownership.
2. **Warehouse milestone:** correct line-level expected/ASN allocation; explicit partial quantities; multi-batch quality; operational provenance; restricted discrepancy intake; immutable receipt evidence, idempotency/concurrency and coordinated reversal. Complete MM-04-01/02/03/04 warehouse paths and required stock/cost/report links.
3. Deliver direct receipt plus minimum recipient custody/confirmation with sections 05/06/07/09. MM-04-05 remains mandatory; build in milestone 1 when shared holder/custody contracts are ready, otherwise name it explicitly as the next dependent slice.
4. Complete import dossier/late cost and equipment completeness/installation (MM-04-06/07). Existing warehouse receipt of imported goods/equipment is regression-tested in milestone 1; specialist completion does not disappear into a generic later backlog.
5. Run all scenarios below across browser/API/PostgreSQL and full relevant regression. Close each requirement only when its evidence is linked, including dependent slices. External provider acceptance remains separately unverified until tested against the authorized provider.

## 9. Executable business acceptance scenarios

Use isolated test company C1, warehouse W1/site S1, supplier V1 and scoped users Receiver R, Quality Q, Buyer B, Finance F, Recipient P and Auditor A. All ordinary examples use base UOM EA, unit price 10 UZS, open period and no opening stock. Each scenario asserts API results **and** receipt/stock/quality/cost/PO/ASN/schedule/outbox database facts; browser journeys exercise the same commands. Reference IDs below are fixture aliases, not arbitrary existing database IDs.

| ID / requirement | Given → action | Expected, including exact quantities |
|---|---|---|
| A01 / MM-04-01 | PO line M1=10, M2=20; ASN lines/commitment schedules match; receive only M2=5. | M1 schedule received=0/open=10; M2 received=5/open=15; receipt value=50. Reversing M2=2 restores M2 open=17 and value=30. Repository iteration order cannot change results. |
| A02 / MM-04-01 | Freeze business date 2026-09-29; schedule 10 EA due 2026-09-28, accepted 4; amend remaining due to 2026-10-01 with authorized reason. | Before amendment overdue open=6; after amendment future open=6, past delivery history retained. Expected-inbound counts PO/ASN coverage once. Due today is not overdue. |
| A03 / MM-04-02 | PO/ASN 100; post partial 40 then 35 with distinct keys/versions. | Stock=75/value=750; PO accepted=75/open obligation=25; two receipts and correct ASN allocations; no shortage case without shipment-complete assertion. Retry first key adds 0. |
| A04 / MM-04-02/03 | Shipment expectation 30 of PO 100; physically arrive 28: accepted 25, damaged 3; mark shipment complete. | Physical=28, shortage=2 against shipment, PO unarrived=72; owned accepted value=250 subject to quality policy; damaged 3 remain restricted custody or recorded refused-at-gate disposition; claim/exception 3+2, no stock write-off for 2. Replacement entitlement is authorized explicitly; no automatic 72-unit shortage. |
| A05 / MM-04-03 | PO/ASN allowance 10; physically receive 12, 10 conforming + excessPending 2. | 10 accepted/value=100 and 2 supplier-owned restricted custody; excess case=2, invoice evidence <=10. Approved amendment +2 and acceptance decision changes owned total to12/value120 without a second physical arrival. Alternate return of2 leaves owned10 and custody0. |
| A06 / MM-04-03/04 | Receive 10 accepted-on-hold in two batches 4+6 under incoming-inspection policy. | Physical owned=10, available=0, held=10, both batch allocations have inspection evidence. Q releases batch4 only: available4, held6. Serial issue from held batch fails. This must fail against the current null-movement multi-batch gap until fixed. |
| A07 / MM-04-04 | Receive 2 serial units S-001/S-002 in lot L1, manufacturer V2, origin UZ, manufactured 2026-08-01, expires 2027-08-01, certificate C1. | Receipt and operational batch have identical dates/provenance; 2 units link to receipt/batch and value20. Duplicate normalized S-001 in another concurrent receipt fails atomically. Conflicting L1 expiry is blocked for review, not overwritten. |
| A08 / MM-04-04 | Arrival1 in expired lot (expiry 2026-09-28), business date 2026-09-29; separate attempt manufacture 2026-10-01 with expiry 2026-09-01. | Expired arrival may record restricted physical1, available0, quality case1; cannot issue. Impossible date ordering is rejected before posting. Shelf-life policy violation is a structured hold/block reason. |
| A09 / MM-04-05 | Direct PO10 to S1/project J1; observe10, then assigned P confirms6 and refuses1, leaving3 pending; default title-at-acceptance. | Central W1 stock=0; site custody owned6/value60, supplier-owned pending3/refused1; accepted PO=6/open obligation4; 6 pending-consumption custody units, consumption0. Later confirm3 → owned9/value90, unresolved refusal1. Repeat confirmation key adds0; unrelated recipient cannot confirm. |
| A10 / MM-04-05 | Same direct receipt10, contractual title-at-dispatch; recipient has not confirmed. | Owned restricted site stock10/provisional value100, usable0, recipient-confirmed0, consumption0. Acknowledgement6 reclassifies6 only; does not add another10 or recognize receipt twice. |
| A11 / MM-04-06 | Import10 at10; freight20 UZS, duty10 UZS, recoverable tax5. Receive10; approved quantity allocation. | Acquisition cost130/unit13; tax5 excluded; source charges20+10 counted once even if customs declaration also shows duty10. Repeat allocation command adds0. Dossier totals reconcile to source ledger and receipt allocations. |
| A12 / MM-04-06/09 | Receive10 at10; issue4 before approved freight20 arrives. | Remaining6 inventory increases by12; consumed4 cost correction=8; total acquisition120, remaining value72 and consumed cost48. No full20 capitalization onto remaining6. Direct-consumed and asset receipt variants allocate to their own cost objects. |
| A13 / MM-04-06 | Import ASN10 arrives before customs clearance; declaration releases6; remaining4 await release. | Physical10 tracked; release/use limit6; held4. Delivered milestone alone permits no use. Close is blocked for unresolved clearance/evidence, then allowed only after4 release and required receipt/cost reconciliation. |
| A14 / MM-04-07 | One equipment unit E1 at100 with mandatory motor1, guards2 and manual1; arrive motor1/guard1/manual1. | E1 completeness missing guard1; store held equipment with identity/custodian, do not create a second unit. Subsequent guard1 completes E1; approved acceptance creates exactly1 asset at100 (component value is part of kit, not additive). |
| A15 / MM-04-07 | Dispatch complete E1 with motor1/guards2/manual1 to installation order I1; receiver acknowledges unit and all components. | Warehouse custody leaves exactly1 unit; installation recipient holds exactly1 with components. Pending handoff before acknowledgement is visible. Commissioning is separate; acknowledgement does not start depreciation. Missing guard blocks commissioning unless approved waiver is linked. |
| A16 / all posting paths | Two users concurrently attempt receive7 against open10 using different keys and same version. | Exactly one posts7, other conflicts; final physical/accepted7, open3, value70, one receipt/outbox group. Retrying winner returns same IDs. Changed payload with winner key conflicts. |
| A17 / MM-04-02 reversal | Receive10; no downstream use; approved reversal3. Then issue2 of remaining7 and attempt reversal6. | After first reversal stock7/value70, accepted7/open3, original10 immutable and linked reversal3. Second request blocked because only5 eligible unconsumed; no partial mutation. Closed-period original requires current-period correction, not editing its posting date. |
| A18 / permissions/audit | C1 receiver references C2 ASN/attachment/location; buyer without receiver role posts; receipt creator approves own excess; recipient submits another user's confirmation. | Each denied with 0 stock/cost/fulfillment writes and no foreign document disclosure. Authorized retries still enforce scope. Auditor can follow source→allocation→decision→posting→reversal with actor/reason. |
| A19 / replacement | PO10 physically delivers8 good+2 rejected at gate; Buyer authorizes replacement2 linked to rejection; next ASN2 arrives good. | Physical observations total12, accepted fulfillment10/value100, rejected2 historically visible, original order quantity10 unchanged, remaining obligation0. A third replacement attempt without new authorized entitlement fails. |

### Verification implementation after approval

Preserve and extend existing tests: backend `src/test/java/com/enterprise/mm/service/PurchaseOrderReceiptEvidenceTest.java`, `PurchaseOrderReceiptIdempotencyTest.java`, `DeliveryScheduleSafetyHardeningTest.java`, `AssetAcceptanceOperationalFlowTest.java`, `LandedCostAllocationServiceTest.java`; `src/test/java/com/enterprise/mm/warehouse/receiving/WarehouseReceiptPostingServiceTest.java`; `SupplyLogisticsImportTest.java`, `SerializedTmbPostgresInvariantTest.java`. Existing tests are source evidence only; they were not run in this design chat.

Add PostgreSQL integration tests `ReceiptAllocationPostgresTest.java`, `ReceiptDispositionPostgresTest.java`, `DirectDeliveryConfirmationPostgresTest.java`, `ReceiptReversalPostgresTest.java`, `ImportDossierReceiptPostgresTest.java`, `EquipmentCompletenessPostgresTest.java` under `src/test/java/com/enterprise/mm/warehouse/receiving/`. Include simultaneous transactions with different keys, serial uniqueness, FK tenant/company failures, rollback after stock/cost/outbox fault, closed periods and migration upgrade fixtures. H2/mocks alone do not establish these invariants.

Extend frontend `src/features/goods-income/{receivingContract.test.ts,PurchaseOrderReceivingPage.test.tsx,api.test.ts}` and fixed-asset tests. Extend `mm-front/e2e/goods-income-receipt.spec.ts`; add `e2e/direct-delivery-confirmation.spec.ts`, `e2e/import-dossier-receipt.spec.ts`, `e2e/equipment-completeness.spec.ts` against a seeded local PostgreSQL-backed API, asserting persisted values and role changes, not intercept-only UI success.

Future plan commands must run in coordinator-assigned isolated checkouts/databases/output directories. Expected runners are Maven selected-test invocation and frontend `npm test -- <selected paths>` / `npm run test:e2e:live -- <scenario path>` (verify config includes each new path), followed by build/localization checks. No tests or builds were run now into shared `target`, `dist` or Playwright outputs. Completion evidence must link actual passing commands, migration results and browser artifacts; external delivery/signature/customs providers remain unverified until separately exercised.

## 10. Design self-review and handoff

- All seven MM-04 requirement IDs have current source evidence, missing behavior, proposed contracts and numeric acceptance criteria.
- Physical observation, ownership, commercial acceptance, quality release, recipient custody and consumption are distinct; one event cannot silently substitute for another.
- Warehouse milestone is bounded; direct delivery, import dossier and equipment installation remain explicit required dependent slices.
- Defaults requiring business approval are enumerated. Detailed code/test steps and exact migration version are intentionally reserved for the approved writing-plans phase and coordinator file ownership, not silently chosen in a shared repository.
- Key integration decisions for coordinator: base-UOM and stock key contract; replacement entitlement versus physical PO counters; per-allocation quality/receipt eligibility; title-at-delivery versus title-before-confirmation; cost allocations to consumed/site/asset destinations; equipment handover purpose and recipient custody; one authorization matrix.
