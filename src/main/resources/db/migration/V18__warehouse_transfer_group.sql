-- Warehouse Transfer Groups for multi-destination parent requests
CREATE TABLE IF NOT EXISTS warehouse_transfer_groups (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    company_id UUID NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(40) NOT NULL,
    source_warehouse_id UUID NOT NULL,
    planned_date DATE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    created_by VARCHAR(120),
    updated_by VARCHAR(120),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_wtg_tenant_company_code UNIQUE (tenant_id, company_id, code)
);

CREATE INDEX IF NOT EXISTS idx_wtg_tenant_source ON warehouse_transfer_groups(tenant_id, company_id, source_warehouse_id);

CREATE TABLE IF NOT EXISTS warehouse_transfer_group_items (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    company_id UUID NOT NULL,
    group_id UUID NOT NULL REFERENCES warehouse_transfer_groups(id) ON DELETE CASCADE,
    destination_warehouse_id UUID NOT NULL,
    material_id UUID NOT NULL,
    planned_quantity NUMERIC(22, 7) NOT NULL,
    child_transfer_id UUID,
    child_transfer_line_id UUID,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    created_by VARCHAR(120),
    updated_by VARCHAR(120),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_wtgi_group ON warehouse_transfer_group_items(group_id);
CREATE INDEX IF NOT EXISTS idx_wtgi_child_transfer ON warehouse_transfer_group_items(child_transfer_id);
