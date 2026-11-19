CREATE TABLE IF NOT EXISTS warehouse_installation_positions (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    company_id VARCHAR(50) NOT NULL,
    project_id UUID NOT NULL,
    material_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    account_code VARCHAR(20) NOT NULL DEFAULT '07',
    status VARCHAR(40) NOT NULL,
    dispatched_quantity NUMERIC(19, 7) NOT NULL DEFAULT 0,
    accepted_quantity NUMERIC(19, 7) NOT NULL DEFAULT 0,
    capitalized_quantity NUMERIC(19, 7) NOT NULL DEFAULT 0,
    returned_quantity NUMERIC(19, 7) NOT NULL DEFAULT 0,
    source_reference VARCHAR(120),
    dispatched_date DATE,
    accepted_date DATE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_warehouse_install_proj_mat 
    ON warehouse_installation_positions (tenant_id, company_id, project_id, material_id);

CREATE INDEX IF NOT EXISTS idx_warehouse_install_wh_acc 
    ON warehouse_installation_positions (warehouse_id, account_code);
