-- Bulk 1C catalog and opening imports can leave company cardinalities stale.
-- Refresh planner statistics before serving reports: otherwise allocation anti-joins
-- can repeatedly scan the entire company ledger when PostgreSQL estimates one row.
-- ANALYZE changes statistics only; stock, valuation and historical rows are untouched.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ANALYZE stock_movements;
ANALYZE stock_movement_allocations;
ANALYZE stock_cost_movements;
ANALYZE stock_balances;
ANALYZE materials;
ANALYZE material_groups;
ANALYZE warehouses;
ANALYZE unit_of_measures;
ANALYZE goods_receipts;
ANALYZE purchase_orders;
ANALYZE suppliers;
ANALYZE issue_requests;
ANALYZE users;
