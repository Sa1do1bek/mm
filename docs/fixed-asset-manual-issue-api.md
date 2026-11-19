# Fixed asset manual issue: FIFO/LIFO and Details allocations

Base path: `/api/workspaces/warehouse-execution/manual/fixed-asset-stock`.
The authenticated user's assigned warehouse is resolved on the server. Do not send a warehouse ID.

## Details and preview

`GET /issue-options?materialId={id}&pickingMethod=FIFO&page=0&size=25`
returns `data.positions` with `stockBalanceId`, batch number, receipt/expiry dates,
location/bin IDs, on-hand/available/executable quantities, `selectable`, and `blockedReason`.
Use `pickingMethod=LIFO` to show newest receipts first. Page size is 1–200.

`POST /issue-preview` accepts JSON with the same body as the issue command below.
It returns the resolved `data.lines[].allocations` without changing stock. Opening
Details can leave the line in `AUTO`; switch to `MANUAL` when the user applies
explicit source quantities.

## Issue command

`POST /issue` accepts `multipart/form-data`: a JSON part named `request`, optional
`files` parts, and a required `Idempotency-Key` header. Example `request` part:

```json
{
  "movementDate": "2026-11-07",
  "source": "Recipient",
  "reference": "Optional reference",
  "lines": [
    {
      "materialId": "00000000-0000-0000-0000-000000000001",
      "quantity": 3,
      "pickingMethod": "FIFO",
      "allocationMode": "MANUAL",
      "allocations": [
        {
          "stockBalanceId": "00000000-0000-0000-0000-000000000002",
          "quantity": 2
        },
        {
          "stockBalanceId": "00000000-0000-0000-0000-000000000003",
          "quantity": 1
        }
      ]
    }
  ]
}
```

- `pickingMethod`: `FIFO` (default) or `LIFO`.
- `allocationMode`: `AUTO` (default) or `MANUAL`. Existing requests without the new fields still work.
- `AUTO`: omit `allocations` or send an empty list. Stock is selected by `pickingMethod`.
- `MANUAL`: provide nonempty `allocations`; each `stockBalanceId` must be unique,
  match the line's material and assigned warehouse, and be available for issue.
  The selected sources override the automatic FIFO/LIFO source choice.
- All quantities must be positive whole numbers, and allocation quantities must
  sum to the line quantity. Fractions are rejected even if the catalog UOM allows them.
- Use `stockBalanceId` from options, not `stockBatchId`. Do not select serial units:
  fixed asset stock facts track quantities rather than individual inventory cards.
- Posting revalidates current available stock under locks. A successful preview
  does not reserve stock. Retry the same command with the same idempotency key;
  changing the command requires a new key.

Receipt behavior is unchanged: picking/allocation fields are ignored on `/receipt`.

## Localized stock picking errors

Material and fixed asset issues return HTTP 422 for these stock picking errors.
Send `Accept-Language: uz`, `ru`, or `en` to receive the corresponding `message`.
The `errorCode` is independent of the language:

| Cause | errorCode |
| --- | --- |
| Insufficient stock, including a manual source quantity exceeding available stock | `STOCK_PICKING_INSUFFICIENT` |
| Selected stock position unavailable for issue | `STOCK_PICKING_UNAVAILABLE` |
