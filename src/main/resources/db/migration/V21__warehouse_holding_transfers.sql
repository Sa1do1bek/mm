-- Migration V19: Inter-Holder Transfers and Statements for Materials in Use (Account 014)
-- Tracks transfers between responsible employees (MOL) and departments with strict quantity conservation.

ALTER TABLE warehouse_holdings ADD COLUMN IF NOT EXISTS transferred_in_quantity NUMERIC(22, 7) NOT NULL DEFAULT 0;
ALTER TABLE warehouse_holdings ADD COLUMN IF NOT EXISTS transferred_out_quantity NUMERIC(22, 7) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS warehouse_holding_transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(80) NOT NULL,
    company_id UUID NOT NULL,
    transfer_number VARCHAR(100) NOT NULL,
    source_holding_id UUID NOT NULL,
    target_holding_id UUID NOT NULL,
    material_id UUID NOT NULL,
    from_department_id UUID,
    from_employee_id UUID,
    to_department_id UUID,
    to_employee_id UUID,
    quantity NUMERIC(22, 7) NOT NULL,
    unit_cost NUMERIC(19, 6) NOT NULL DEFAULT 0,
    total_value NUMERIC(19, 4) NOT NULL DEFAULT 0,
    reason VARCHAR(255),
    idempotency_key VARCHAR(160) NOT NULL,
    transferred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_wh_holding_transfer_idempotency UNIQUE (tenant_id, company_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_wh_transfer_scope ON warehouse_holding_transfers(tenant_id, company_id, transfer_number);
CREATE INDEX IF NOT EXISTS idx_wh_transfer_source ON warehouse_holding_transfers(source_holding_id);
CREATE INDEX IF NOT EXISTS idx_wh_transfer_target ON warehouse_holding_transfers(target_holding_id);
