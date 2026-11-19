-- V22: Custody Transfers and Ownership Settlement (Schet 002 to 10xx).
-- Records settlement/buyout of third-party custody stock into company ownership without physical duplication.

CREATE TABLE IF NOT EXISTS warehouse_custody_ownership_transfers (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    company_id UUID NOT NULL,
    command_id VARCHAR(160) NOT NULL,
    material_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    source_owner_id UUID NOT NULL,
    source_account_code VARCHAR(40) NOT NULL DEFAULT '002',
    target_account_code VARCHAR(40) NOT NULL DEFAULT '1010',
    quantity NUMERIC(22, 7) NOT NULL,
    unit_settlement_price NUMERIC(19, 4) NOT NULL,
    total_settlement_value NUMERIC(19, 4) NOT NULL,
    settlement_date DATE NOT NULL,
    settlement_reference VARCHAR(120),
    status VARCHAR(50) NOT NULL DEFAULT 'COMMITTED',
    code VARCHAR(80),
    name TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    is_delete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(120),
    updated_by VARCHAR(120),
    CONSTRAINT uk_custody_transfer_command UNIQUE (tenant_id, company_id, command_id)
);

CREATE INDEX IF NOT EXISTS idx_custody_transfers_lookup
    ON warehouse_custody_ownership_transfers (tenant_id, company_id, material_id, warehouse_id, source_owner_id);
