# Returnable packaging: frontend handoff

Backend scope completed in this change. This file is the implementation brief for the frontend; no frontend code is changed here.

## User journey

1. Create a quantity-tracked material with `materialSubtype = RETURNABLE_PACKAGING` (drum, crate, container). Keep oil and its drum as separate materials and units of measure.
2. Receive the contents and packaging in one purchase order or manual warehouse receipt. For a manual receipt, the packaging is posted only after the final approval, together with the contents.
3. In **Returnable packaging**, show the packaging balance by supplier, material, content material, warehouse, owner, condition, and due date. A lot stays on hand while the contents are used.
4. Record `FILLED → PARTIAL → EMPTY` as drums are emptied. `FILLED → EMPTY` is also supported. Record damage with a reason. Optionally link a posted content issue movement as evidence of use.
5. Create a supplier return for available empty drums, approve it, dispatch with a document number, and record supplier confirmations. A failed or unaccepted dispatch can be recalled with a reason; if some units were confirmed, recall the entire remaining unconfirmed quantity.

## Receipt forms

Purchase order receipt `packaging[]` line:

```json
{
  "contentPurchaseOrderItemId": "<PO item UUID>",
  "packagingMaterialId": "<returnable packaging material UUID>",
  "quantity": 5,
  "ownership": "COMPANY",
  "unitCost": 100000,
  "dueDate": "2027-01-28"
}
```

Manual receipt accepts `packaging[]` on both the batch and single receipt request. A manual line uses the **zero based** index of its contents in the submitted `lines[]`; the single receipt has index `0`:

```json
{
  "contentLineIndex": 0,
  "packagingMaterialId": "<returnable packaging material UUID>",
  "quantity": 5,
  "ownership": "SUPPLIER",
  "accountCode": "002",
  "unitCost": 75000,
  "dueDate": "2027-01-28"
}
```

Show the ownership choice before account/value fields. The `unitCost` field is required for **both** ownership choices, accepts zero or a positive value, and supports at most four decimal places. It is the value of one packaging unit and must stay separate from the contents cost.

For supplier property, keep account `002` fixed but show an editable required unit-cost input. Do not set or reset it to `0` when the user selects `SUPPLIER`. An active custody agreement for the supplier is required. The backend retains this cost on the packaging lot and sends it through custody receipt, dispatch, and recall postings; ownership remains `SUPPLIER` and stock category remains `CUSTODY`.

For company property, do not ask the receipt user to choose an account. The backend resolves the separate returnable-packaging stock account configured in Inventory Accounting Settings. `GET /api/stock-account-resolution/materials/{materialId}` can be used to show it read-only. Omit `accountCode` from new company receipt payloads. The backend still accepts a matching code for transition compatibility but rejects a stale/mismatching code. New receipts post packaging quantity and value to the resolved account in the `OWNED` warehouse subledger; approved `WAREHOUSE / RECEIPT / OWNED` and `WAREHOUSE / ISSUE / OWNED` posting rules must cover it. Changing configuration affects future receipts only.

### Frontend changes required

1. In `PackagingReceiptEditor`, render **Unit cost** for both `SUPPLIER` and `COMPANY`. Remove company account selection. Show the resolved company account read-only; supplier account remains the fixed value `002`.
2. When ownership changes to `SUPPLIER`, preserve an already entered `unitCost`. If none exists, leave it empty so validation asks the user to enter it; do not inject `0`.
3. When ownership changes back to `COMPANY`, preserve the unit cost and refresh the read-only effective account for the selected packaging material.
4. Update draft validation so supplier rows require a finite, nonnegative `unitCost` with at most four decimals, exactly like company rows. Keep the supplier account rule `accountCode === "002"`.
5. Update purchase-order and manual payload builders to send the entered supplier `unitCost` unchanged. Remove any supplier-specific `unitCost: 0` override. Omit `accountCode` for company ownership; `002` may also be omitted for supplier ownership because the backend assigns it.
6. Update labels/help text in Russian, English, and Uzbek. Remove text claiming supplier cost is zero. Explain that account `002` is fixed and the entered value is the unit value of the supplier-owned packaging held in custody.
7. Add UI tests for entering a nonzero supplier unit cost, switching ownership without losing it, validation of missing/negative/over-precision values, and both PO/manual payloads carrying the exact value.
8. In the Returnable Packaging register and lot detail, display the backend response fields `unitCost` and `amount` for both ownership types, formatted as UZS. Do not recalculate `amount` in the client and do not infer ownership from whether either value is zero.
9. Extend the frontend `AccountPostingMode` union to `OWNED_ACCOUNT_SUBLEDGER | CUSTODY_SUBLEDGER | LEGACY_STOCK_MOVEMENT`. Treat the legacy value as read-only history; new company receipts should return `OWNED_ACCOUNT_SUBLEDGER`.

Backend validation errors to surface at the field/form level:

- missing, negative, or more than four decimals: `Packaging unit cost is required and must be non-negative with at most four decimals`;
- supplier account other than `002`: `Supplier-owned packaging must use custody account 002`;
- missing custody setup: the existing custody-agreement business error.

## Register layout

Use two tabs: **Packaging balances** and **Supplier returns**. Preserve filters, sort, page, and page size in the URL as on other warehouse pages. Show a compact active-filter summary and a clear-filters action. Apply filters server side, allow page sizes 20/50/100, and show total count. Show separate fields for `onHandQuantity`, `emptyQuantity`, `reservedQuantity`, and `awaitingSupplierConfirmation`; do not add these together. `onHandQuantity` includes filled, partial, empty, and damaged drums still in the warehouse.

### Packaging balances

`GET /api/warehouse/returnable-packaging` returns the existing `PageResponse<LotView>` envelope. Added query parameters:

| Parameter | Values |
| --- | --- |
| `search` | Lot code, supplier name, packaging material name, content material name; at most 120 characters |
| `supplierId`, `materialId`, `contentMaterialId`, `warehouseId` | UUID filters |
| `ownership` | `COMPANY`, `SUPPLIER` |
| `sourceType` | `PURCHASE_ORDER`, `MANUAL_WAREHOUSE` |
| `state` | `FILLED`, `PARTIAL`, `EMPTY`, `DAMAGED` (lots with a positive quantity in that state) |
| `dueFrom`, `dueTo` | ISO dates |
| `onlyOnHand` | Boolean |
| `sortBy` | `createdAt`, `code`, `dueDate`, `receivedQuantity`, `emptyQuantity`, `dispatchedQuantity` |
| `sortDir` | `asc`, `desc` |
| `page`, `size` | Zero based page; size 1–100 |

Default sort is newest first, with a stable ID tie breaker. The lot response includes `accountCode`, `unitCost`, and backend-calculated `amount = receivedQuantity × unitCost` for both ownership types. It also adds `warehouseName`, `createdAt`, `sourceType`, `manualDocumentId`, `manualContentMovementId`, `dueDate`, and `accountPostingMode`: `OWNED_ACCOUNT_SUBLEDGER` for new company-owned lots, `CUSTODY_SUBLEDGER` for supplier-owned lots, and `LEGACY_STOCK_MOVEMENT` for company lots created before account-aware posting. Use `warehouseId` as a fallback if the warehouse name is unavailable. PO sourced lots retain `goodsReceiptId` and `goodsReceiptLineId`; these are `null` for manual receipts. Link each row to its source document and to its event timeline. Supplier-return register rows embed the same lot response, so `row.lot.accountCode`, `row.lot.unitCost`, and `row.lot.amount` are available there too.

Export the **entire filtered result**, not merely the visible page, through `GET /api/warehouse/returnable-packaging/export` with the same filters and sort. The response is UTF-8 CSV with a header and Excel BOM.

### Supplier returns

`GET /api/warehouse/returnable-packaging/returns` returns `PageResponse<ReturnRegisterView>` where each row has `document` and `lot` objects. Query parameters: `search`, `supplierId`, `materialId`, `warehouseId`, `ownership`, `status`, `sortBy`, `sortDir`, `page`, `size`. Search covers return code, dispatch document number, lot code, supplier, and packaging material. Status choices: `DRAFT`, `APPROVED`, `DISPATCHED`, `PARTIALLY_CONFIRMED`, `CONFIRMED`, `CANCELLED`, `RECALLED`, `PARTIALLY_RECALLED`. Sort fields: `createdAt`, `code`, `status`, `quantity`, `confirmedQuantity`, `recalledQuantity`, `dispatchedAt`. Add filters and sortable column headers as on other pages. Export all matching rows through `GET /api/warehouse/returnable-packaging/returns/export` with the same filters and sort.

Suggested columns: return code, lot, supplier, packaging material, owner, quantity, confirmed, recalled, outstanding, status, dispatch document, dispatch date, and actions. Display `outstanding = quantity - confirmedQuantity - recalledQuantity` for dispatched/partially confirmed documents. Show each action only in its valid status and for permitted roles.

## Lot actions and evidence

- `POST /api/warehouse/returnable-packaging/{lotId}/state`: `{ "quantity": 2, "fromState": "FILLED", "toState": "EMPTY", "expectedVersion": 0, "reason": "Used contents", "contentMovementId": "<optional posted issue UUID>" }`. The linked movement must be a posted **issue** of this lot's content material in the same warehouse. A reason is mandatory for a transition to `DAMAGED`. Refetch the lot after a version conflict.
- `GET /api/warehouse/returnable-packaging/{lotId}/events`: event rows now include `reason` and `contentMovementId` for audit display. Return lifecycle event types include `RETURN_CREATED`, `RETURN_APPROVED`, `DISPATCHED`, `SUPPLIER_CONFIRMED`, `RETURN_CANCELLED`, and `DISPATCH_RECALLED`.
- Existing draft, approval, dispatch, confirm, and cancel endpoints keep their current contracts. A return can reserve only available empty packaging. Dispatch requires a document number and reduces on hand stock. Supplier confirmation changes the acknowledgement count, not physical stock.
- `POST /api/warehouse/returnable-packaging/returns/{returnId}/recall`: `{ "expectedVersion": 0, "reason": "Supplier rejected remaining drum", "quantity": 1 }`. `quantity` may be omitted; then all outstanding units are recalled. It must equal the entire outstanding quantity. After partial supplier confirmation, status becomes `PARTIALLY_RECALLED`; with no confirmation it becomes `RECALLED`. The response adds `recalledQuantity` and `createdAt`.

## Acceptance walkthrough

1. Receive **1,000 L oil and 5 drums**; check that oil stock is 1,000 L and the drum material balance is 5 EA. Enter a nonzero unit cost for both ownership choices. Supplier-owned drums use custody `002` and retain the entered cost; company-owned drums use valued stock and an active account reference.
2. Leave the five drums `FILLED` for two months. They should remain on hand in the register, including when oil is issued. The state changes only through an explicit packaging action.
3. Mark two drums `EMPTY`, optionally linking the oil issue. Confirm the register shows 3 filled, 2 empty, 5 on hand. Create and approve a return for 2; both become reserved.
4. Dispatch 2 with a document number; on hand becomes 3. Confirm 1; one remains awaiting supplier confirmation. Recall the other 1 with a reason; on hand becomes 4, confirmed stays 1, recalled is 1, and the return is `PARTIALLY_RECALLED`.
5. Check filters, sorting, pagination, both CSV exports, row links, event reasons, and the warehouse role's scope restriction.
