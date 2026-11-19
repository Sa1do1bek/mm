# Warehouse workspace stock tab: frontend handoff

Scope: `/app/warehouse-execution` → `Остатки на складе`. Backend endpoints are ready under `/api/workspaces/warehouse-execution/accounting-balances`. The warehouse assignment is enforced on every request. Users with a company-wide warehouse role must send the selected `warehouseId`; assigned warehouse users may omit it. A different warehouse is rejected.

## Data and filter

- `GET /api/workspaces/warehouse-execution/accounting-balances?warehouseId=<uuid>&accountCode=1017&search=<text>&materialGroupId=<uuid>&asOfDate=YYYY-MM-DD&page=0&size=10`
- Omit optional filters to see all accounts or materials. Omit `asOfDate` for today. The response is `ApiResponse<WarehouseReport>`: use `data.content` for rows and `data.totalElements` for pagination.
- Row fields: `key`, `materialId`, `materialCode`, `materialName`, `accountCode`, `accountName`, `closingQuantity`, `closingAmount`, `uom`, localized unit names. `closingQuantity` and `closingAmount` are exact decimal **strings**; keep them as strings until display formatting. `data.currencyCode` is the amount currency, normally `UZS`; `data.accountSnapshotDate` identifies the accounting snapshot. A material can occur under several accounts, so do not key rows by `materialId` alone.
- `GET /api/workspaces/warehouse-execution/accounting-balances/accounts?warehouseId=<uuid>&search=<text>&page=0&size=50` returns `ApiResponse<PageResponse<{id,code,name}>>` for the selected warehouse. Use `code` as `accountCode` in the list and export requests. Replace the stock tab's warehouse selector with this account selector; keep the selected or assigned warehouse in API requests. The movements tab can keep its existing filters.
- `GET /api/workspaces/warehouse-execution/accounting-balances/export.xlsx` accepts the same filters plus `language=ru|uz|en`. It exports all matching rows and keeps the selected warehouse access check.
- When no completed 1C account snapshot exists for the company, list, accounts and export return `WAREHOUSE_REPORT_ACCOUNTS_UNAVAILABLE`. Show the backend error; do not display the operational stock total as an account balance.

## Stock tab presentation

1. Render one quantity column from `closingQuantity`, one account column from `accountCode`, and one amount column from `closingAmount` with `data.currencyCode`. Remove the duplicate “Можно выдать” / “На складе” columns for this accounting view.
2. Put the existing material information trigger beside the material name, on the same line. Make the icon or name clickable and remove the separate “Подробнее” line. Reduce row padding to match the compact layout in the request.
3. Feed the search, material group, account and as-of date into list and export together. Reset page to zero when any filter changes. The selected warehouse remains the source of truth even though its filter is hidden on the stock tab.
4. The existing `MaterialStockInfoButton` shows operational physical availability. If reused, label it as material/physical stock information so the accounting amount or account-specific quantity is not implied. The report's accounting quantity may differ from operational `available`/`onHand` because the sources have different scopes.

Implementation touchpoints: `mm-front/src/features/warehouse-execution/WarehouseRoleHome.tsx`, `WarehouseStockFilters.tsx`, and the new API/types used by that stock tab. The current stock tab calls `getInventoryAvailabilityPositions` and `exportInventoryToExcel`; replace those calls for this tab with the endpoints above. No frontend files were changed in this backend task.
