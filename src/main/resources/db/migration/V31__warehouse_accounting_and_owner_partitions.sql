-- Migration V29: Warehouse Accounting Positions, Owner Stock Partitions, and Asynchronous Export Jobs
-- 1. Owner & Stock Category first-class partitioning on stock_balances (P1.1)
ALTER TABLE stock_balances ADD COLUMN IF NOT EXISTS owner_party_id UUID;
ALTER TABLE stock_balances ADD COLUMN IF NOT EXISTS stock_category VARCHAR(30) DEFAULT 'OWNED' NOT NULL;

CREATE INDEX IF NOT EXISTS idx_stock_balances_owner_cat 
ON stock_balances(tenant_id, company_id, warehouse_id, material_id, owner_party_id, stock_category);

-- Recreate uk_stock_balance_key with full tenant, company, owner and category partitions
DROP INDEX IF EXISTS uk_stock_balance_key;
CREATE UNIQUE INDEX uk_stock_balance_key ON public.stock_balances USING btree (
    tenant_id, company_id, material_id, warehouse_id,
    storage_location_id, storage_bin_id, stock_batch_id,
    owner_party_id, stock_category
) NULLS NOT DISTINCT WHERE (is_delete = false);

-- 2. Dedicated Warehouse Accounting Positions (P1.2)
CREATE TABLE IF NOT EXISTS warehouse_accounting_positions (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    company_id UUID NOT NULL,
    posting_batch_id UUID NOT NULL REFERENCES warehouse_posting_batches(id),
    warehouse_id UUID NOT NULL,
    material_id UUID NOT NULL,
    account_code VARCHAR(20) NOT NULL,
    stock_category VARCHAR(30) NOT NULL DEFAULT 'OWNED',
    owner_party_id UUID,
    quantity NUMERIC(22, 7) NOT NULL DEFAULT 0,
    amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency_code VARCHAR(10) NOT NULL DEFAULT 'UZS',
    effective_date DATE NOT NULL,
    analytic_snapshot JSONB,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wh_acct_pos_lookup 
ON warehouse_accounting_positions(tenant_id, company_id, account_code, effective_date);

CREATE INDEX IF NOT EXISTS idx_wh_acct_pos_material 
ON warehouse_accounting_positions(tenant_id, company_id, material_id, effective_date);

-- 3. Asynchronous Export Jobs Table (P7.2)
CREATE TABLE IF NOT EXISTS warehouse_export_jobs (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    company_id UUID NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    format VARCHAR(10) NOT NULL DEFAULT 'xlsx',
    file_name VARCHAR(255),
    requested_by UUID,
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    progress_percent INT NOT NULL DEFAULT 0,
    file_path VARCHAR(500),
    file_size BIGINT,
    error_message TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_wh_export_jobs_tenant_comp 
ON warehouse_export_jobs(tenant_id, company_id, status);

-- 4. Warehouse Activation Plans & Approvals (Blocker 2)
CREATE TABLE IF NOT EXISTS warehouse_activation_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(80) NOT NULL,
    company_id UUID NOT NULL,
    plan_code VARCHAR(80) NOT NULL,
    account_code VARCHAR(40),
    warehouse_id UUID,
    posting_date DATE NOT NULL,
    preview_hash VARCHAR(64) NOT NULL,
    source_content_hash VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED', 'APPLIED')),
    item_count INT NOT NULL DEFAULT 0,
    total_quantity NUMERIC(22, 7) NOT NULL DEFAULT 0,
    total_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    proposed_by VARCHAR(120),
    proposed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_by VARCHAR(120),
    approved_at TIMESTAMPTZ,
    rejection_reason TEXT,
    manifest_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT uk_wh_activation_plan_code UNIQUE (tenant_id, company_id, plan_code)
);

CREATE INDEX IF NOT EXISTS idx_wh_activation_plans_scope 
ON warehouse_activation_plans(tenant_id, company_id, account_code, warehouse_id, status);

