# Production operations

This implementation adds native production documents at `/api/warehouse/production-operations` and the frontend module `/app/production-operations`.
It addresses manufacturing, processing, packaging, assembly, stock kitting, cutting and disassembly, including required serial identities and mandatory output inspection. A multi-item shipment continues to use the existing transfer workflow; it does not create a new stock item.

## Completion checklist

- Implemented: quantity-based multistage production, exact native costing, inter-workshop transfer ancestry and component history.
- Implemented in V58: exact serialized component selection, output unit registration, production unit events and transactional unit reversal.
- Implemented in V58: mandatory production output inspection, quarantine, native sampling/laboratory decisions, quantity-based partial acceptance and serial-lot full acceptance/rejection.
- Pending: independent configurable production approvals and separation between preparer and reviewer.
- Pending: recipe/BOM versions, planning/scheduling, WIP/partial completion and planned-versus-actual yields.
- Pending: explicit responsible-person handover/fixed-asset acceptance links.
- Pending: older processing/production receipt adapters, MES adapters and any historical backfill.

## Stock and costing authority

New operations use `StockMovementService`, normal stock batches, valuation policy, cost layers, movement allocations, period checks and inventory freeze checks. They do not also call the older account-aware transformation service: posting through two stock engines would duplicate effects.

Each document has a warehouse, date, type, immutable document number, source reference, notes, component inputs and product outputs. Inputs select exact company-owned stock positions. The service checks combined demand when several lines select the same position. Stock units come from the material master; there is no assumption that kilograms, pieces and kits are interchangeable.

Actual input values come from the existing valuation engine. Output weights distribute the actual consumed value plus documented additional costs. Allocation uses four decimal places; the last positive weight receives the remainder. Output movement, physical balance, cost layer and allocation evidence retain the same exact book value, even when a rounded unit price cannot reproduce it by multiplication.

## Lifecycle and API

| Method and path | Behavior |
|---|---|
| `GET /?warehouseId=…&page=0&size=25` | Company- and warehouse-scoped document page; maximum 50 documents per page |
| `GET /{id}` | Draft snapshot, posted lines, actors, values and allowed actions |
| `POST /` | Save draft; requires `Idempotency-Key` |
| `PUT /{id}` | Replace draft with `{version, draft}`; warehouse and number cannot change |
| `POST /{id}/post` | Post `{version}` atomically; requires `Idempotency-Key` |
| `POST /{id}/cancel` | Cancel an unposted draft using `{version}` |
| `POST /{id}/reverse` | Reverse posted document using `{version, reason}` and `Idempotency-Key` |
| `GET /history/materials/{materialId}?warehouseId=…&stockBatchId=…` | Follow component origins and transfer parents through native movement evidence |

States are `DRAFT → POSTED → REVERSED` or `DRAFT → CANCELLED`. Edits and stock commands check the expected version. Posting and reversal serialize with the existing warehouse locks and persist idempotency results in the caller's transaction. Failure rolls back components, products, costs, document lines and operation status together.

Reversal removes outputs before restoring inputs through the existing exact allocation reversal engine. A consumed output cannot be reversed while its downstream consumption remains effective. Generic movement creation, editing, posting and cancellation cannot bypass the production document; its reversal movements cannot be independently cancelled.

Warehouse managers, warehouse heads, MM managers and administrators can manage these documents. Warehouse operators and existing read roles can inspect them within their warehouse scope. Costs are returned only to production managers, finance and auditors. This increment uses manager-controlled posting; it does not introduce or claim a configurable independent production approval route.

## Example draft

IDs below stand for existing company-scoped catalog and stock-position identities. Decimal strings are accepted to retain precision in browser requests.

```json
{
  "operationNumber": "BAGS-001",
  "operationType": "MANUFACTURING",
  "warehouseId": "<workshop A warehouse UUID>",
  "operationDate": "2026-10-28",
  "reference": "Production plan 17",
  "notes": "Produce empty bags",
  "additionalCost": "1000.0000",
  "additionalCostReason": "Workshop processing cost",
  "inputs": [{"materialId": "<film UUID>", "stockPositionId": "<film position UUID>", "quantity": "2.5"}],
  "outputs": [{"materialId": "<empty bag UUID>", "quantity": "100", "costWeight": "1", "lineType": "OUTPUT", "batchNumber": "BAGS-001"}]
}
```

Post the saved draft, transfer and receive its output batch with the existing transfer workflow, then select the received position in Workshop B as an input to a `PACKAGING` document. Bulk product can be another input in that document. For a computer stock kit, select the separate component positions and create a separately cataloged computer-set output.

## History

Posted operation lines explicitly reference their native movements and resulting batches. Product history follows output → component issue → consumed source cost layer → original receipt, recursively crossing earlier production stages and transfer layer parents. History contains actual movement quantities with their own units, not an inferred conversion ratio. Opening balances without original receipt evidence remain incomplete; no origin is invented.

The UI exposes this history from production outputs and the current material stock-details dialog. Historical as-of dialogs do not offer current history as if it were an as-of reconstruction. Traversal stops at 200 movements, respects warehouse access and reports truncation. The browser bounds expansion of shared ancestors.

## Serial identity and output quality (V58)

For a `REQUIRED` serial-tracked input, provide `materialUnitIds`, one UUID per whole physical unit (1–1000 per line). Select the exact stock position first. Posting locks the units and verifies tenant/company, material, warehouse, batch, location, bin and `IN_STOCK` status. A unit cannot appear twice anywhere in a document. Components become `CONSUMED`, with a `PRODUCTION_CONSUMED` event; they cannot be returned through the employee issue-return workflow as if production were an employee issue.

For a `REQUIRED` serial-tracked output, provide `serialNumbers`, one nonblank serial per whole unit, up to 160 characters each. Serial uniqueness follows the existing company/material scope with trimmed uppercase matching. Duplicate serials across output lines or already registered units are refused, including when a competing document posts after the draft was saved. Produced units retain their own `production_stock_movement_id`, rather than claiming a purchase or manual receipt origin. Their `PRODUCTION_CREATED` events and production operations retain the exact document and native movement links.

Posted line responses contain `units: [{id, serialNumber, status}]`, `qualityInspectionId` and `qualityStatus`. The UI displays the component/product identities and links directly to the exact inspection in the current company. Product batch genealogy still follows native movements and transfer cost-layer parents; serial identities are visible on their linked production documents. Existing quantity-only draft serialization is preserved so old idempotent commands keep their request hashes.

Mandatory inspection is created atomically with each output receipt. It has type `PRODUCTION`, a link to the native output movement, and an initial full-quantity quarantine lot. Produced serialized units also enter `QUARANTINE`. The existing quality workspace includes production inspections and retains its sampling → laboratory → authorized decision controls. A direct release before testing is refused. Accepted quantity moves to available stock; rejected quantity remains quarantined. No supplier or purchase order is invented for a production inspection. Production inspection source/quantity evidence cannot be reassigned, edited or deleted through generic inspection endpoints.

Serialized production inspection currently requires full-lot acceptance or rejection. To make separate quality decisions, use separate output lines (for example one computer per line), each with its own native receipt and inspection. A mixed decision on a serialized lot is refused atomically; it does not arbitrarily choose which serial was accepted. Quantity-tracked lots support the existing mixed acceptance/rejection allocation.

Reversal requires every inspection attached to an output to be fully released, with no rejected or quarantined quantity. Pending or rejected output reversal and rejected-output rework/disposal are **not implemented by production**; quarantine remains effective. Once released, native stock/value reversal still blocks any effective downstream consumption or transfer. Serialized reversal removes the exact output units (`DISPOSED`, with `PRODUCTION_REVERSED` events) and restores the exact consumed components to `IN_STOCK`, preserving original identities and value. Removed output serials remain registered as historical identities. Reversing an operation does not erase its inspection, laboratory, movement or unit evidence.

Serial execution continues to require the existing `SERIALIZED_EXECUTION` deployment capability. This change does not modify deployment capabilities, inventory locks, period checks, catalog controls or the transfer approval policy.

The current native transfer receipt workflow from main moves each physical unit to the actual destination receipt batch before writing its receive event. Accepted and damaged serials use separate explicit selections; missing or misclassified units are refused before posting. Production uses this native workflow and preserves immutable production/purchase origins. No historical unit/batch mismatch is silently backfilled. Native transfer quarantine/disposition remains separate from production-output inspection.

Integration with main on 2026-10-29 preserves its V57 goods-receipt UOM migration and numbers production serial/quality as V58. The duplicate branch-only UOM migration was removed. An earlier version of this branch used V57 for production; any test database that applied that old numbering needs explicit migration-history reconciliation or a fresh isolated test database. No database repair or history rewrite is performed by this change.

## Deliberately unavailable

- Individual serial choices within a mixed accepted/rejected quality lot; use separate output lines for independent decisions.
- Reversal while output inspection is pending or contains rejected/held quantity; rejected-output production rework/disposal.
- Repair of historical serial-transfer batch mismatches. New serialized receipts use main's accepted/damaged unit selections and destination-batch handling.
- Independent approval routes and reviewer separation for production documents.
- Production scheduling, recipes/BOM versioning, WIP between partially completed stages and planned-versus-actual yield controls.
- Links to specific responsible-person handover or fixed-asset acceptance documents; produced batch stock can use existing eligible downstream flows.
- Retrofit of the older `/processing/*` and `/production-receipts` implementations or external MES integrations.

Deploy backend migrations through V58 before enabling this frontend increment. Physical transfers between workshops depend on the existing `app.warehouse.transfers.shipment-flow-enabled` deployment setting. Integration tests enable it only in their isolated configuration; this change does not alter its deployment default. Existing documents and legacy transformations are not reinterpreted or backfilled.

## Verification

`ProductionOperationPostgresTest` uses an isolated PostgreSQL 16 container with all migrations and real stock/costing services. It covers multistage genealogy, exact rounding, assembly, insufficient stock, company boundaries, rollback, duplicate requests, concurrent posting, downstream reversal protection and physical transfer between workshops. Transfer approval is prepared test data in the transfer integration scenario; approval behavior is not certified by that scenario.

Focused native movement/costing regression tests cover the new authoritative output-value path. Frontend checks cover precision, roles, requests, production document links, browser retries and visible component history. Windows reserves port 5175 on some machines; the Playwright configuration now accepts `PLAYWRIGHT_PORT`, with the existing default preserved.

Verified on 2026-10-28: 37 focused backend tests passed (including 11 PostgreSQL scenarios), 72 frontend tests passed, three Playwright journeys passed, focused ESLint passed and the production frontend build passed. Browser journeys use mocked HTTP responses; they do not certify a deployed frontend/backend combination. The repository-wide test suite was not run. No deployment or live inventory changes were performed.

Pre-integration verification on 2026-10-28 (production was then numbered V57): 95 focused backend tests passed, including 18 real PostgreSQL 16 scenarios with Flyway/schema validation. The scenarios cover serial assembly and exact reversal, duplicate/wrong-batch rejection, rollback of stock/value/units/inspections/quarantine lots, native laboratory results, mixed quantity release, full serialized acceptance/rejection, and native serial transfer followed by processing in the destination workshop. The transfer scenario also checks that unsupported damaged serial receipt rolls back without moving its unit out of transit. Existing movement/costing, serial eligibility, capability, quality workspace and transfer policy checks passed. Frontend: 41 focused unit tests, five mocked Playwright journeys, focused ESLint and the production build passed. The build retains the existing large-chunk warning. These are focused automated checks; the repository-wide suite and deployed manual acceptance have not been run. No shared services or live inventory were changed.

After integrating main on 2026-10-29: 80 focused backend checks passed, including all 18 PostgreSQL production scenarios, migration/schema validation through V58, native transfer receipt validation/transaction tests, serial eligibility and stock/costing checks. Frontend integration checks passed: production build, 41 focused unit tests and five mocked browser journeys. The transfer regression now rejects missing/misclassified accepted/damaged unit selections using main's native validation and confirms a valid received production serial can be processed in another workshop. No deployed acceptance, database-history repair, shared-service deployment or live inventory changes were performed.

## Manual acceptance checklist

### Prepare the application and catalog

1. Run both backend and frontend from their `codex/production-completion` branches for this increment. `Sardor` remains the phase-one manual-test branch. Restart the backend with Flyway enabled and confirm migrations V56 and `V58__production_serials_and_quality.sql` completed. Start the frontend against that backend; a frontend update alone does not add the new APIs or database tables.
2. Use a test company and two active warehouses representing Workshop A and Workshop B. Log in as an administrator or MM manager for the complete cross-workshop exercise. Warehouse managers and heads need an actual assignment to the warehouse they operate.
3. Check that `TRANSFER_SHIPMENT_FLOW_ENABLED=true` for the transfer exercise. The current base application configuration already defaults this to true, unless the environment overrides it. Use the existing transfer approval process with an eligible independent approver when approval is required.
4. Prepare active TMB catalog materials with approved stock units: film in KG, empty bags in EA, bulk product in KG, and filled bags in EA. Output materials must already exist in the catalog. Use quantity-tracked materials without mandatory inspection for the basic numerical exercise below; use the additional checklist for serials and inspection. Existing approved, available input stock may have passed inspection.
5. Receive film into Workshop A and bulk product into Workshop B through the normal receipt workflow. A draft receipt, reservation or stock in transit is not available production input. For the numerical example, receive 10 KG film at 10,000 UZS/KG and 100 KG bulk product at 500 UZS/KG, without extra receipt costs. If using existing stock, expect the configured valuation policy's actual costs instead of these example values.

### Bags: manufacturing, transfer and packaging

1. Open **Warehouse execution → Production and assembly**, select Workshop A and choose **New operation**.
2. Enter a unique document number and choose **Manufacturing**. Select film, its available source batch/position, and quantity **1 KG** as the input. Select empty bags, quantity **10 EA**, cost allocation weight **1**, and an optional batch number as the output. Enter **2,000 UZS** additional cost and a reason.
3. Save the draft. Confirm that film remains **10 KG** and no bags have been created yet. Reopen the saved document and verify the material names, units, source position and quantities.
4. Choose **Post operation** and confirm. Expected result: film becomes **9 KG**; a new batch contains **10 empty bags**. With the example input costs, the output batch's total book value is **12,000 UZS**. The document shows who created and posted it.
5. Open **Origin and production history** on the output. Follow the bag receipt to the film issue and its original receipt. The same current-history entry point is available in material stock details.
6. Use **Transfer products / send a bundle** to create a normal transfer of the **10 empty bags** to Workshop B. Submit it, complete the existing approval process, dispatch the produced batch and confirm receipt in Workshop B. Production at Workshop B must wait for actual receipt.
7. Create a **Packaging** operation in Workshop B. Inputs: **10 received empty bags** and **100 KG bulk product**, selecting each exact position. Output: **10 filled bags**, cost allocation weight **1**, with no additional processing cost for this example.
8. Post it. Expected result in Workshop B: empty bags **0**, bulk product **0 KG**, filled bags **10 EA**. With the example costs, the filled bags' total book value is **62,000 UZS**. Open their history and verify both inputs, Workshop A's bag manufacture, the warehouse transfer ancestry and the original receipts.

### Computer stock kit and a shipment bundle

1. Prepare quantity-tracked TMB catalog items for a monoblock, keyboard, mouse and a separate computer-set product. Receive the three components into one warehouse.
2. Create **Stock kit** or **Assembly**. Inputs: one monoblock, one keyboard and one mouse from available positions. Output: one computer-set product, with cost allocation weight **1**. Post it.
3. Confirm each component decreases by one and the computer-set batch increases by one. Open its history to see all three components and their source receipts. The output value is their actual consumed book value plus any documented additional costs.
4. To send the set to another workshop, transfer the new computer-set batch. For sending separate items together without creating a new stock product, use one existing transfer with three component lines instead. A shipment bundle does not create a computer-set balance or a new assembly history.
5. This module does not automatically capitalize a computer as a fixed asset or record it as issued to an employee. Those downstream links remain separate work.

### Serialized computer assembly and inspection

1. Keep real catalog controls enabled. Prepare a serial-required TMB monoblock, quantity-tracked keyboard/mouse and a separately cataloged serial-required computer set, all in EA. Enable mandatory inspection on the computer set if testing quality. Confirm `SERIALIZED_EXECUTION` is available in the test environment.
2. Obtain available component stock through normal authorized receipts (and inspection release if required). Record the monoblock's real serial. A draft receipt, quarantined unit or unit in transit is not an available component.
3. Create an **Assembly** document. Select each component's exact position. For the monoblock, additionally select its physical serial. Output one computer set with a new serial such as `SET-TEST-001`, weight 1 and optional batch number. Save the draft: all stock and unit states must remain unchanged.
4. Post. Confirm the selected monoblock becomes **Consumed**, the keyboard/mouse decrease by one and the computer set has its own stock balance and physical identity. Its book value equals actual components plus documented extra cost. Open its history and the linked production document to inspect component identities and source movements.
5. If inspection is mandatory, confirm the new set shows **Quarantine**, available quantity 0 and on-hand quantity 1. Open its inspection link. Confirm type **PRODUCTION**, the correct movement/batch and no invented supplier/PO. Attempting production, transfer or reversal before release must fail.
6. In the existing quality workflow, start sampling, create a laboratory test, start/complete it with a result, then make the quality decision as an administrator or MM manager. A direct quality release before the laboratory result must fail. Full acceptance makes both the balance and physical unit available. Full rejection keeps both quarantined.
7. For two serials on one output line, try accepting one and rejecting one: the decision must fail and preserve both units/quarantine balances. For independent decisions, create two one-unit output lines before posting instead. Quantity-tracked output lots still support mixed decisions.
8. Transfer a fully released computer set to Workshop B using the existing transfer approval, exact-unit dispatch and receipt workflow. Confirm the same serial arrives, its production origin remains linked and its history includes the source workshop. Approval preparation in automated transfer tests does not certify the complete approval UI.
9. On a separate unused assembly, reverse with a reason after any inspection is fully released. Confirm the exact components and their values return; the produced serial becomes **Disposed** with production reversal evidence. Repeating the same reversal request must not repeat stock or unit events. A consumed/transferred set must block reversal until downstream effects are undone.
10. Try a duplicate output serial (including changed case/outer spaces), repeat one component serial on two lines and select a unit from another batch. Each must fail without creating partial stock, costs, serials or inspection rows. Do not disable real serial/quality controls to pass a test.

### Controls to exercise

- Cancel an unposted draft: all stock must remain unchanged.
- Try posting more than the available input quantity: posting must fail, the document must stay a draft, and no products or partial component issues may remain.
- Reopen or refresh a posted document: it must not create stock again. If a request fails temporarily, retry the same operation from the confirmation dialog; do not create a second document to retry it.
- Reverse a newly posted, unused operation with a reason: output stock is removed and the exact original input quantities and values are restored. The original document and reversal evidence remain visible.
- Try reversing an earlier operation after its output has been consumed or transferred: it must be blocked while the original output's cost allocation is unavailable. A simple receipt of replacement stock does not undo the original downstream allocation.
- As a warehouse operator, inspect documents and history in the assigned warehouse: no create, post or reverse controls should appear, and cost fields are withheld. An unassigned operator must not gain access to another warehouse.
- Required serial tracking must demand exact identities. Mandatory inspection must create full quarantine atomically, and rejected units must remain unavailable. Disabling real catalog controls is not a valid workaround.

### Local startup reference

Use the existing backend environment configuration, with the `local` Spring profile if running locally. That profile requires database and application secrets already configured for your environment. Do not paste credentials into committed files.

```powershell
# In mm-backend, using the existing local environment:
mvn spring-boot:run '-Dspring-boot.run.profiles=local'

# In a separate terminal, in mm-front, explicitly target the local backend:
$env:VITE_API_URL=''
$env:VITE_BACKEND_URL='http://localhost:8080'
npm run dev -- --host 127.0.0.1 --port 4173
```

Open `http://127.0.0.1:4173/app/production-operations`. If testing a shared environment, use its deployed application URL after both services have been updated. This checklist has not been executed against shared or production inventory.
