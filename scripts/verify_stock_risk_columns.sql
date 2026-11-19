-- Run once against the target database BEFORE deploying.
-- Target schema: public, matching the project migrations.
-- This checks column existence only, not types, permissions, or index validity.
-- Expected result: 0 rows. Any returned row is a column that StockRiskAlertQueryRepository uses
-- but that does not exist in the current schema.
WITH required (table_name, column_name) AS (VALUES
    ('materials', 'id'),
    ('materials', 'tenant_id'),
    ('materials', 'company_id'),
    ('materials', 'is_delete'),
    ('materials', 'status'),
    ('materials', 'code'),
    ('materials', 'name'),
    ('materials', 'unit_of_measure_id'),
    ('materials', 'min_stock'),
    ('materials', 'safety_stock'),
    ('materials', 'reorder_point'),

    ('stock_balances', 'tenant_id'),
    ('stock_balances', 'company_id'),
    ('stock_balances', 'warehouse_id'),
    ('stock_balances', 'material_id'),
    ('stock_balances', 'is_delete'),
    ('stock_balances', 'available_quantity'),

    ('stock_movements', 'tenant_id'),
    ('stock_movements', 'company_id'),
    ('stock_movements', 'warehouse_id'),
    ('stock_movements', 'material_id'),
    ('stock_movements', 'is_delete'),
    ('stock_movements', 'status'),
    ('stock_movements', 'movement_type'),
    ('stock_movements', 'posted_at'),
    ('stock_movements', 'quantity')
)
SELECT r.table_name, r.column_name AS missing_column
FROM required r
LEFT JOIN information_schema.columns c
       ON c.table_schema = 'public'
      AND c.table_name = r.table_name
      AND c.column_name = r.column_name
WHERE c.column_name IS NULL
ORDER BY r.table_name, r.column_name;
