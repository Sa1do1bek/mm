CREATE TABLE IF NOT EXISTS warehouse_production_corrections (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    company_id VARCHAR(50) NOT NULL,
    parent_event_id VARCHAR(120) NOT NULL,
    correction_event_id VARCHAR(120) NOT NULL,
    event_version INT NOT NULL,
    material_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    account_code VARCHAR(20) NOT NULL DEFAULT '28',
    delta_quantity NUMERIC(19, 7) NOT NULL,
    delta_cost NUMERIC(19, 4) NOT NULL DEFAULT 0,
    reason VARCHAR(240),
    posting_receipt_id VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wh_prod_correction_event UNIQUE (tenant_id, company_id, correction_event_id)
);

CREATE INDEX IF NOT EXISTS idx_wh_prod_corr_parent 
    ON warehouse_production_corrections (tenant_id, company_id, parent_event_id);
