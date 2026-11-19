# Returnable packaging workflow

Returnable packaging is a separate TMB material with subtype `RETURNABLE_PACKAGING`.
The purchase-order receipt request may include `packaging[]` lines. Each line names
an accepted content `purchaseOrderItemId`, a packaging material, a whole-unit
quantity, ownership (`SUPPLIER` or `COMPANY`), account code and unit cost. The
content and packaging stock postings run in the same purchase-order receiving
transaction. A failed packaging posting rolls back the entire receipt.

## Ownership and value

- `SUPPLIER`: requires an active custody agreement whose owner ID is the
  supplier ID, and approved `WAREHOUSE / RECEIPT / CUSTODY` and
  `WAREHOUSE / ISSUE / CUSTODY` posting rules for account `002`. This stock is
  held in the supplier's `CUSTODY` partition with zero company asset value.
- `COMPANY`: requires an explicit non-002 account code and unit cost. The
  packaging receives its own valued stock movement. Enter only its separately
  agreed value; do not include the same amount in both content and packaging.

The account code for company-owned packaging is retained on the lot for
reconciliation. The standard owned-stock movement engine handles its physical
quantity and valuation. Deposit, refund and payable settlement remain outside
this warehouse workflow and must follow the contract/accounting process.

## Register and actions

`GET /api/warehouse/returnable-packaging` lists lots, optionally filtered by
supplier, packaging material and warehouse. Each lot links its content receipt,
supplier, ownership, stock posting and quantity states. `GET /{lotId}/events`
shows the immutable action trail.

1. On receipt, all packaging is `FILLED`; the stock balance includes every unit.
2. `POST /{lotId}/state` moves whole units `FILLED → PARTIAL/EMPTY/DAMAGED`,
   `PARTIAL → EMPTY/DAMAGED`, or `EMPTY → DAMAGED`. This does not change
   physical stock. Emptying is recorded by the warehouse operator because a
   bulk content issue does not identify which individual container became empty.
3. `POST /returns` creates a draft from available empty units. `POST
   /returns/{id}/approve` reserves them. `POST /returns/{id}/dispatch`
   requires a dispatch document number and issues packaging stock, leaving
   the content balance unchanged.
4. `POST /returns/{id}/confirm` records the supplier's accepted quantity,
   including partial confirmation. It does not issue stock again. An
   unconfirmed dispatch can be restored to stock with `POST
   /returns/{id}/recall` and a reason. Draft/approved returns may be
   cancelled before dispatch.

State and dispatch commands require the current `expectedVersion`; stale
updates fail. The database enforces that received quantity equals full,
partial, empty, damaged and dispatched quantities together. Reservations
cannot exceed empty quantity; confirmations cannot exceed dispatches.

## Current scope

Existing 1C owned stock can also be adopted as `LEGACY_OPENING` lots. The reviewed
backfill links each lot to its existing stock balance, batch and exact opening
movement. It copies the stored book value and changes only the material subtype;
it posts no stock, valuation, payable or general-ledger movement. Company-wide
stock fingerprints and non-classification material fingerprints must match
before and after, or the transaction rolls back. The migration receipt is
available to administrators at `GET /api/warehouse/returnable-packaging/legacy-migrations`.

Opening lots start with `unknownQuantity`; supplier and contents remain absent
when the source does not establish them. Warehouse staff classify unknown units
as full, partly full, empty or damaged with a reason. A supplier return from an
opening lot requires an explicitly selected, company-scoped receiving supplier.
That recipient belongs to the return document and does not overwrite the unknown
original supplier of the opening stock.

The initial workflow uses whole-unit, quantity-tracked packaging. Individual
serial numbers, warehouse transfers and automatic identification of emptied
containers are not part of this flow. A supplier-owned receipt will fail
atomically until its custody agreement and approved account 002 rules exist.
