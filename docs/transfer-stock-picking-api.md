# Transfer stock picking: FIFO/LIFO and manual allocations

This contract supports dispatch directly from the transfer document's material table.
Each row has an editable quantity, a FIFO/LIFO selector, and an optional Detail button.
The existing dispatch drawer and legacy explicit-batch request remain compatible.

## Scope and permissions

All endpoints use `/api/transfers/{id}` and require dispatch authority at the source
warehouse, an approved/dispatchable transfer, and reconciled shipment evidence.
The shipment-flow feature must be enabled. Tenant and company isolation apply at
service level, including preview and stock options.

Picking is physical source selection. `pickingMethod` does not change the material's
accounting `valuationMethod`. Costing and transit evidence continue through the
existing managed transfer posting pipeline, restricted to the selected batches.

## Table context

`GET /api/transfers/{id}/dispatch-context`

Returns `transferDocumentId`, `expectedVersion`, `calculatedAt`, and `lines` with:

- `transferLineId`, `materialId`, `materialName`, `unitOfMeasureId`, `unitCode`, `quantityScale`.
- `remainingQuantity`: planned minus already dispatched and cancelled quantities.
- `eligibleAvailableQuantity`: executable owned stock at the source warehouse.
- `maxDispatchQuantity`: minimum of the two quantities above.
- `defaultPickingMethod`: `FIFO`.

Multiple lines for the same material share the same stock pool. Per-line maxima
are individual limits, not independently guaranteed reservations. Preview validates
the combined request and prevents spending the same position twice.

## Detail / stock options

`GET /api/transfers/{id}/lines/{lineId}/stock-options?pickingMethod=FIFO&page=0&size=25`

`pickingMethod` accepts `FIFO` and `LIFO`. Size is 1..200. The response contains
`totalElements`, page metadata, and ordered `positions`. A position identifies the
exact `stockBalanceId`, `stockBatchId`, `storageLocationId`, and `storageBinId`.
It includes batch number, receipt/expiry dates, on-hand, available and executable
quantities, `selectable`, `blockedReason`, and eligible serial unit IDs/numbers.

Blocked/exhausted positions remain visible for explanation. Only positive,
selectable executable quantities may be allocated. Executable stock excludes
other-owner/custody stock, expired or blocked positions, and unavailable serial
units. The stored available quantity already excludes reservations and quarantine;
do not subtract those quantities a second time.

Order uses warehouse batch `receivedDate`, then batch creation time, batch ID and
balance ID. FIFO sorts dates ascending; LIFO sorts them descending. Missing dates
stay last in both methods. Allocation uses the entire eligible stock pool, not only
the visible page. Position quantities are floored to stock UOM precision, never
rounded upward.

For receipt provenance/cost history, reuse:

- `GET /api/materials/{materialId}/command-view/movements?warehouseId=...&stockBatchId=...`
- `GET /api/materials/{materialId}/command-view/stock-detail?warehouseId=...&stockBatchId=...`
- `GET /api/materials/{materialId}/command-view/movements/{movementId}/allocations?warehouseId=...`

These endpoints retain their existing field permissions and pagination. Cost-layer
remaining quantities are evidence, not a substitute for executable position stock.

## Preview and dispatch

`POST /api/transfers/{id}/dispatch-preview` uses the same body as dispatch:

```json
{
  "expectedVersion": 3,
  "dispatchedDate": "2026-10-26",
  "lines": [
    {
      "transferLineId": "<transfer-line-uuid>",
      "dispatchedQuantity": 10,
      "pickingMethod": "FIFO",
      "allocationMode": "AUTO"
    }
  ]
}
```

`quantity` is also accepted as a JSON alias for `dispatchedQuantity`.
Omitted `pickingMethod` defaults to FIFO. Explicit AUTO/MANUAL requests must contain
one row per transfer line. AUTO rejects nonempty manual allocations and legacy
source fields. Omitting `allocationMode` selects the legacy explicit-batch format,
not an inferred AUTO mode.

Preview returns the current `expectedVersion`, `calculatedAt`, and per-line
selection decisions with exact source allocations and serial unit IDs. Preview
never mutates stock, creates shipments, or reserves quantities. It does not validate
all posting-period/freeze/cost-evidence constraints; final posting remains authoritative.

For MANUAL, replace the row with:

```json
{
  "transferLineId": "<transfer-line-uuid>",
  "dispatchedQuantity": 10,
  "pickingMethod": "LIFO",
  "allocationMode": "MANUAL",
  "allocations": [
    { "stockBalanceId": "<position-a-uuid>", "quantity": 6 },
    { "stockBalanceId": "<position-b-uuid>", "quantity": 4 }
  ]
}
```

Each quantity must be positive and within UOM precision (up to seven decimals).
Manual allocation quantities must sum exactly to the row quantity. Missing amounts
are not silently filled from other batches. Required serial materials also need
`materialUnitIds` per manual allocation, exactly one distinct eligible unit per
whole quantity. AUTO chooses eligible serial units in receipt order (reverse for
LIFO) within the selected batch/position.

`POST /api/transfers/{id}/dispatch` additionally requires `Idempotency-Key`.
It recalculates AUTO under stock locks, revalidates MANUAL exactly, then uses the
existing atomic posting pipeline. A stale manual selection fails without substituting
another batch or partially posting. Insufficient stock is rejected even if global
negative stock is enabled. Retry the identical request with the same key; a changed
body requires a new key after an already successful command.

The immutable shipment stores `pickingDecisions` per line: method, AUTO/MANUAL mode,
quantity and resolved source allocations. Actual posted allocations and cost lineage
remain the accounting source of truth. Pre-existing shipments have an empty list.
Migration: `V53__transfer_picking_decisions.sql`.

## Legacy compatibility

The existing flat request rows with `transferLineId`, `dispatchedQuantity`,
`stockBatchId`, location/bin and optional `materialUnitIds` continue to work.
Multiple rows per transfer line may select distinct positions. They are recorded
as manual decisions. Do not mix legacy and AUTO/MANUAL rows for the same line.

## Frontend behavior

- Keep Detail optional. Opening it alone does not change AUTO to MANUAL.
- Use table context to set quantity input precision and maximum.
- For quantity/method changes, debounce preview and discard out-of-order responses.
- Switching to AUTO removes manual overrides. For an edited MANUAL quantity, require
  rebalancing allocations or an explicit user action to reset to AUTO.
- A global selector may apply a method to every row, but the submitted method is per line.
- Disable submit while preview is invalid or pending. Refresh context and preview
  after a stale-version or insufficient-stock response.
- Carry `dispatchedDate` on the first screen; the removed drawer previously collected it.

## Concurrency and costing after review

Outbound cost-layer locks are acquired in ascending receipt/creation/ID order for
both FIFO and LIFO. LIFO selection happens in memory after acquiring those locks.
Transfer dispatch prelocks the relevant cost layers before balance rows, matching
ordinary outbound costing. Balance, source-batch and exact serial locks used by
transfer dispatch do not wait behind conflicting row locks. Contention returns
HTTP 409 (`stockPicking.busy`) and rolls the entire command back. Refresh stock and
retry; the same idempotency key can be reused when the prior attempt rolled back.

Exact serial dispatch re-reads the locked unit state and compares location and bin
including nulls. Null means that exact position, not a wildcard. Serial stock is
not selectable when the deployment disables `SERIALIZED_EXECUTION`.

FIFO/LIFO and weighted posting consume remaining book value, including the final
money quantum, rather than treating a rounded unit-price snapshot as authoritative.
The accounting method itself remains governed by the existing accounting settings.
