CREATE TABLE IF NOT EXISTS warehouse_installation_capitalizations (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    company_id VARCHAR(50) NOT NULL,
    position_id UUID NOT NULL REFERENCES warehouse_installation_positions(id),
    capitalization_command_id VARCHAR(120) NOT NULL,
    fixed_asset_id UUID NOT NULL,
    capitalized_quantity NUMERIC(19, 7) NOT NULL,
    capitalized_amount NUMERIC(19, 4) NOT NULL,
    capitalization_date DATE NOT NULL,
    source_reference VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_warehouse_install_cap_pos 
    ON warehouse_installation_capitalizations (tenant_id, company_id, position_id);

CREATE INDEX IF NOT EXISTS idx_warehouse_install_cap_fa 
    ON warehouse_installation_capitalizations (fixed_asset_id);
