# Manual issues before stock import

`POST /api/workspaces/warehouse-execution/manual/stock-issue/unmatched` records a completed material handover when its physical source warehouse and stock position are unknown. It uses a multipart `request` JSON part and optional `files` parts, with a required `Idempotency-Key`. The request contains `entryContextWarehouseId`, `issueDate`, `issuedTo`, optional `reference`, and one to 100 `{materialId, quantity}` lines. The entry context is checked for active warehouse access but is **not** stored as the physical source warehouse.

The response is a `POSTED_UNMATCHED` record with a generated code, recorder/time, material code/name/unit snapshots and quantities. `sourceWarehouseId` remains null. No stock balance, cost layer, physical unit status or stock movement is changed. Posted evidence cannot be deleted. The same scoped route supports `GET ?entryContextWarehouseId=...` (latest 50) and `GET /{id}`. Records are company and tenant scoped; listing and detail reads enforce the entry context warehouse access.

This is an operational handover record, not a stock ledger posting. A later reconciliation must establish the true warehouse and imported stock evidence before any stock ledger correction is made. The existing `/stock-issue` endpoint remains the path for immediate stock-backed posting and continues to require eligible available stock and picking.

Migration `V100__manual_unmatched_issues.sql` adds a header and immutable line snapshots. It must be applied with the backend release; a frontend deployed before this endpoint continues to receive an error when attempting this new issue path.
