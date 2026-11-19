CREATE TABLE IF NOT EXISTS warehouse_production_receipts (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    company_id VARCHAR(50) NOT NULL,
    event_id VARCHAR(120) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    content_hash VARCHAR(64) NOT NULL,
    material_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    account_code VARCHAR(20) NOT NULL DEFAULT '28',
    quantity NUMERIC(19, 7) NOT NULL,
    unit_cost NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_cost NUMERIC(19, 4) NOT NULL DEFAULT 0,
    quality_disposition VARCHAR(40) NOT NULL DEFAULT 'APPROVED',
    receipt_date DATE NOT NULL,
    production_order_ref VARCHAR(120),
    posting_receipt_id VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wh_prod_receipt_event UNIQUE (tenant_id, company_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_wh_prod_receipt_mat_wh 
    ON warehouse_production_receipts (tenant_id, company_id, material_id, warehouse_id);
