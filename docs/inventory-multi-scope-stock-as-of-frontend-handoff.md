# Frontend handoff: inventory scope and stock cut-off

Status: implemented locally and covered by focused backend tests; not deployed. PostgreSQL migration verification remains pending because a local Docker/PostgreSQL service was unavailable. AI commission comparison remains pending.

## Create and update count

The existing `POST /api/inventory-documents` and update endpoint accept these additional fields:

```json
{
  "name": "1749 order — selected work units",
  "inventoryType": "PLANNED",
  "plannedStartDate": "2026-10-20",
  "plannedEndDate": "2026-11-20",
  "stockAsOfDate": "2026-10-01",
  "scopeMode": "DEPARTMENTS",
  "departmentIds": ["<department-uuid-1>", "<department-uuid-2>"],
  "warehouseIds": [],
  "commissionId": "<inventory-commission-uuid>"
}
```

Choose **one** scope mode per count:

| Mode | Send | Backend resolves |
| --- | --- | --- |
| `DEPARTMENTS` | One or more `departmentIds`, empty `warehouseIds` | Union of all active warehouses mapped to those departments, deduplicated |
| `WAREHOUSES` | One or more `warehouseIds`, empty `departmentIds` | Exactly those warehouses |

For either new mode, omit legacy `warehouseId` and `globalWarehouse` or send `globalWarehouse: false`. Do not mix department and manual warehouse selections. A department without any active mapped warehouse, an inactive/out-of-company warehouse, or a warehouse the user cannot access is rejected. Location/bin filters require exactly one effective warehouse. Existing clients that omit `scopeMode` continue using legacy single-warehouse or all-company behavior.

The response includes `scopeMode`, `departmentIds`, `selectedWarehouseIds`, and `warehouseIds` (the **effective resolved warehouse list**), alongside the old fields. Show the effective list before the user starts the count. It is fixed for a started count; changing a draft/planned count's department selection refreshes the list. A count may be visible only to users authorized for its entire effective warehouse set.

## Accounting date and physical count

`stockAsOfDate` is the **book balance cut-off**. For order 1749, show AI's `documentFacts.stockAsOfDate` as a suggested editable value **2026-10-01**. It is neither the order date **2026-10-16** nor the work period **2026-10-20 to 2026-11-20**. Do not use the 25 November reporting deadline as planned end.

The count response returns `stockAsOfDate` and `snapshotAt`. On each count line:

- `asOfQuantity` and `asOfValue`: book balance on the cut-off date, when provided;
- `systemQuantity`: operational balance captured when the count starts;
- `movementDelta`: operational balance minus cut-off book balance (net recorded change between those bases);
- `countedQuantity`: observed physical quantity;
- `differenceQuantity`: physical minus **operational** balance.

Display the book cut-off and operational snapshot distinctly. Do not calculate a physical discrepancy by subtracting a 1 October quantity from a late-October/November physical count. The backend checks recorded movements to derive historical quantities. If its evidence is incomplete, starting a past-date count fails with `HISTORICAL_EVIDENCE_INCOMPLETE`; show a clear “historical balance unavailable” message and let the user review the cut-off date or obtain accounting correction. It never silently substitutes today's balance. If a later posting is backdated into the cut-off period, posting readiness contains `AS_OF_SNAPSHOT_STALE`; adjustments stay blocked. Normal stock writes are frozen while the count is active, so this indicates an exceptional/imported posting that accounting must investigate. There is no self-service rebase action in this release; the count may need controlled correction or recreation. Users without system-quantity visibility do not receive `asOfQuantity`/`asOfValue` on blind count lines.

## Commission integration boundary

The existing commission picker and `commissionId` remain in use. Wait for the new AI contract before adding the comparison table or “Create recommended” flow. That flow will return an editable `CommissionCreateRequest` draft; after user edits and resolves all members to ERP `userId`s, frontend sends the ordinary `POST /api/commissions`, then selects the returned ID on the count. AI analysis itself performs no write.

## Suggested UI checks

1. Select two departments with a shared warehouse: show that warehouse once in the effective list.
2. Select several warehouses directly: show only those warehouses; switching modes clears the other selection.
3. Show `stockAsOfDate` separately from planned dates and `snapshotAt`.
4. In count details, label both cut-off book quantity and current snapshot quantity; retain blind-count masking.
5. Handle `HISTORICAL_EVIDENCE_INCOMPLETE`, `AS_OF_SNAPSHOT_STALE`, no mapped warehouses, and lack of warehouse access without losing the form values.
