# Warehouse report overview and approval implementation plan

## Goal

Add a date-filtered warehouse overview with one row per warehouse, a detailed expandable report, individual approval, and approval of every warehouse in the active period. Approval saves a fixed report snapshot.

## Existing foundation

- `WarehouseReportQuery` and `WarehouseAccountReportQuery` already calculate opening, receipt, issue, and closing quantities and amounts from the operational or imported source.
- `WarehouseReportService` validates periods, applies warehouse access scope, and generates material-level rows.
- `WarehouseReportsWorkspace` already exposes the date filter and material statement table.

## Decisions

- The overview uses the existing `MATERIAL_STATEMENT` calculation. `ostatka` means closing balance at `to`.
- Money totals may be summed in the report currency. Quantities remain separated by unit of measure; the UI displays all unit-specific quantities for each warehouse.
- The detail tree is warehouse → material group → material. Each material shows opening, receipt, issue, and closing values. Existing receipt/issue report views remain available for deeper investigation.
- An approval is scoped to company, warehouse, `from`, and `to`. It saves the full material-level report as JSON plus approver and timestamp. Later source changes do not alter it; another approval creates a new revision if the report content changed. Previous revisions remain selectable in the detail view.
- “Approve all” covers all warehouses in the active period, not just the visible page. It is one backend transaction, so partial approval is not reported as success.
- Approval is allowed for `ADMIN`, `MM_MANAGER`, `WAREHOUSE_MANAGER`, and `WAREHOUSE_HEAD`. Reads keep the existing warehouse data scope.

## Work sequence

1. Add a backend overview query that groups the existing report result by warehouse and unit. Return amount totals, per-unit quantities, warehouse identity, and latest approval metadata.
2. Add a database migration and backend approval service. Save immutable snapshot revisions and provide individual and bulk approval endpoints plus a snapshot read endpoint. Check period, warehouse scope, role, and idempotency on the server.
3. Add a warehouse overview tab in the frontend. Use From–To dates, one row per warehouse, clear unit labels, approval status, individual approval, and approve-all with an affected-warehouse count.
4. Add a detail tree. Before approval show the live material-level report; after approval show the saved snapshot and its approver/version. Keep existing receipt/issue views reachable for movement investigation.
5. Verify mixed units, transfer directions, historical/account source, empty periods, warehouse-scoped users, duplicate approval, changed-data revision, and all-or-nothing bulk behavior. Run backend and frontend focused tests/build checks.

## Files expected to change

- Backend: `warehouse/reports/WarehouseReportQuery.java`, `WarehouseAccountReportQuery.java`, `WarehouseReportService.java`, `WarehouseReportController.java`; new overview/approval DTOs and service; next Flyway migration; focused report tests.
- Frontend: `features/warehouse-reports/api.ts`, new overview component and copy/styles, `WarehouseReportsWorkspace.tsx`, focused API/UI tests.
