# Transfer picking and existing costing review — 2026-10-26

## Scope

Reviewed the new transfer AUTO/MANUAL allocator against existing stock movement
posting, inventory cost layers, valuation policy, material units, reservations,
ownership partitions, shipment/transit evidence and reversals. Changes remain in
the current checkout; the review does not modify a live database.

## Architecture and source of truth

| Responsibility | Existing component / behavior |
| --- | --- |
| Physical quantity | `StockBalance`: warehouse, material, batch, location, bin, owner, category |
| Transfer quantity | Remaining plan, after dispatched and cancelled quantities |
| Physical selection | `StockPickingAllocationService`, using a separate `PickingMethod` |
| Accounting policy | `InventoryValuationPolicyService`; accounting settings decide the effective method |
| Actual consumed value | `InventoryCostingService` and persisted layer-consumption evidence |
| Shipment / transit | `TransferPostingService`, `TransferShipmentAllocation`, transit ledger |
| Historical source/value | Movement allocations and their cost lineage; reversals preserve recorded evidence |
| Read-only explanation | `FifoLayerAllocationService` reads recorded evidence; it is not an allocation engine |

The physical FIFO/LIFO switch never rewrites accounting settings. Physical ordering
uses the batch warehouse receipt date; costing ordering uses layer receipt time.
Both use stable tie-breakers and keep unknown dates last. Costs within an explicitly
selected batch follow the effective accounting method. In the existing posting
implementation ACTUAL_COST, MOVING_AVERAGE and WEIGHTED_AVERAGE share the pooled
weighted consumption path. This review preserves that policy mapping.

## Defects corrected

1. **Cost/balance lock inversion.** Transfer picking previously locked balances,
   then called costing. Ordinary outbound posting acquired cost layers first,
   then balances. Transfers now prelock costs before balances. Cost locks use the
   same ascending receipt/creation/ID order regardless of FIFO/LIFO; LIFO selection
   is applied after locking. Capitalizable-layer ordering also has the ID tie-breaker.
2. **Later lock cycles / contention.** Balance, source-batch and exact serial locks
   in transfer dispatch use NOWAIT. A busy source causes a whole-command rollback
   and an explicit retryable 409 instead of waiting while holding earlier locks.
   PostgreSQL tests cover busy balance/batch rollback and same-key retry.
3. **Unknown dates in legacy LIFO.** Reversing a nulls-last comparator moved unknown
   dates to the front. A shared `StockPickingOrder` now serves the transfer allocator
   and stock-movement fallback ordering, independently controlling date direction.
4. **Serial nulls used as wildcards.** The generic unit validator intentionally
   supports wildcard locations for some callers. Exact transfer selection now
   compares location/bin including null and retains existing generic semantics.
5. **Stale managed serial/batch entities.** A locking JPA query does not necessarily
   refresh entities previously loaded into the persistence context. Exact serial
   dispatch refreshes the locked units; transfer batch mutation refreshes and
   rechecks batch availability before decrementing. The serial regression moves a
   unit in an independent transaction after the initial read.
6. **Legacy weighted book-value drift.** Subtracting a pooled unit price from each
   differently priced layer and clamping negative balances could create value.
   For two unit layers worth 1 and 100, issuing one pooled unit produced a 50.5
   issue and 74.75 remaining value: 125.25 instead of 101. The existing exact
   book-value allocator now also handles legacy weighted layers, preserving
   `issued value + remaining value = original value`.
7. **Legacy FIFO/LIFO rounding residual.** A rounded unit price could leave a money
   residual in a fully consumed layer, conflicting with exact stock evidence.
   Ordered consumption now uses remaining book value and consumes its final quantum.
8. **Capability-aware availability.** Serial stock is blocked in picker results
   when the deployment disables serialized execution. Blocked options do not expose
   eligible unit lists; cross-scope batch metadata is suppressed.

## Validation coverage

- Physical FIFO/LIFO crossed with FIFO, LIFO, SPECIFIC_BATCH, WEIGHTED_AVERAGE,
  MOVING_AVERAGE and ACTUAL_COST accounting methods; selected sources and total
  source-plus-transit money remain consistent.
- Ordinary issue overlapping transfer dispatch while the issue holds cost locks.
- Shared stock across concurrent transfers; no overdraw or duplicate posting.
- Busy balance/batch/serial handling, rollback and retry.
- Serial first-level-cache refresh, exact location/bin checks and deployment gating.
- Legacy weighted layers with different costs; fractional/recurring unit prices;
  authoritative values, zero-valued stock, exact depletion and money conservation.
- Preview, manual allocation, seven-decimal precision, legacy JSON/idempotency hash,
  permissions, shipment persistence, transit/holding/custody conservation and reversals.

Latest reports: 174 test cases across 23 classes; 0 failures, 0 errors, 0 skipped. The broad run was followed by a targeted serial-test rerun after correcting the test fixture to simulate a versionless external writer; production code was unchanged for that rerun.

## Existing boundaries that remain

- **Generic unbatched stock movements:** the older path still selects cost layers
  before choosing physical balances. Code inspection shows that location, reserved
  stock and physical eligibility can therefore make a cost-layer-selected batch
  unusable even when another position has stock. The new transfer flow avoids this
  by resolving eligible positions and explicit batches first. This review does not
  claim that generic issue/write-off commands have been migrated to the new allocator.
- **Global multi-material lock order:** legacy documents can still post their lines
  in document order rather than one global warehouse/material order. The review
  proves the overlapping single-material issue/transfer case and makes transfer
  contention retryable; it does not claim a global deadlock-free protocol across
  every legacy workflow.
- **Analytical valuation is a separate read model:** `StockValuationService` can
  calculate from batch cost snapshots and balances, while actual posting uses cost
  layers. Those results should not replace posted lineage or be treated as a binding
  dispatch quote. Analytical price methods retain their existing restrictions on
  authoritative opening layers.
- **Preview is physical availability:** posting-period, freeze and complete cost
  evidence checks remain authoritative at final posting. Missing lineage rolls back
  dispatch rather than inventing a price or silently moving to a different source.
- **Scale:** stock-options pagination currently happens after loading the relevant
  material's positions; serial candidates are also read as a scoped pool. Functional
  tests do not establish latency or memory limits for very large warehouses. Query
  pagination, batching and an operational-volume benchmark remain follow-up work.
- Correcting posting arithmetic does not rewrite historical movements or repair
  previously inconsistent book values; those require evidence-backed reconciliation.
