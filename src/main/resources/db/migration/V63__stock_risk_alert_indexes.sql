-- StockRiskAlertQueryRepository indexes (PostgreSQL).
-- Run scripts/verify_stock_risk_columns.sql before deployment: expect zero rows.
-- Flyway companion .sql.conf disables the migration transaction.
-- PostgreSQL session-level Flyway locks are configured in local/prod profiles.
-- Run manually with autocommit; do not wrap in BEGIN or psql --single-transaction.
-- IF NOT EXISTS does not validate an existing index definition or its validity.
-- After an interrupted build, inspect pg_index.indisvalid before retrying;
-- clean up only confirmed invalid indexes before repairing the failed Flyway entry.

-- Balances: aggregated per material, covering index can support index-only scans when visibility permits.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stock_balances_risk
    ON public.stock_balances (tenant_id, company_id, material_id)
    INCLUDE (available_quantity, warehouse_id)
    WHERE is_delete = false;

-- Warehouse-scoped EXISTS / aggregation.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stock_balances_wh_material
    ON public.stock_balances (tenant_id, company_id, warehouse_id, material_id)
    WHERE is_delete = false;

-- Consumption: filtered by type/status/date window, grouped by material.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stock_movements_consumption
    ON public.stock_movements (tenant_id, company_id, material_id, movement_type, status, posted_at)
    INCLUDE (quantity, warehouse_id)
    WHERE is_delete = false;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stock_movements_wh_material
    ON public.stock_movements (tenant_id, company_id, warehouse_id, material_id)
    WHERE is_delete = false;

-- Active materials per company.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_materials_risk_scope
    ON public.materials (tenant_id, company_id, status)
    WHERE is_delete = false;

-- '%text%' search on code/name (LIKE with leading wildcard cannot use a btree).
-- pg_trgm in schema public is installed by V1__baseline_schema.sql.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_materials_code_trgm
    ON public.materials USING gin (lower(code) public.gin_trgm_ops)
    WHERE is_delete = false AND status = 'ACTIVE';
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_materials_name_trgm
    ON public.materials USING gin (lower(name) public.gin_trgm_ops)
    WHERE is_delete = false AND status = 'ACTIVE';
