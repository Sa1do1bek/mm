-- Migration V18: Holdings and Handover (Materials in Use / Account 014)
-- Tracks materials issued into operational use by department and responsible employee/person (MOL)
-- Separates pending handover dispatch from acknowledged active holding quantity.

CREATE TABLE IF NOT EXISTS warehouse_holdings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(80) NOT NULL,
    company_id UUID NOT NULL,
    code VARCHAR(80),
    name TEXT,
    description TEXT,
    material_id UUID NOT NULL,
    department_id UUID,
    employee_id UUID,
    account_code VARCHAR(40) NOT NULL DEFAULT '014',
    owner_id VARCHAR(64) NOT NULL DEFAULT 'OWNED',
    origin_type VARCHAR(40) NOT NULL DEFAULT 'NATIVE_DISPATCH',
    origin_reference VARCHAR(120),
    uom VARCHAR(20) NOT NULL DEFAULT 'PCS',
    pending_quantity NUMERIC(22, 7) NOT NULL DEFAULT 0,
    active_quantity NUMERIC(22, 7) NOT NULL DEFAULT 0,
    consumed_quantity NUMERIC(22, 7) NOT NULL DEFAULT 0,
    returned_quantity NUMERIC(22, 7) NOT NULL DEFAULT 0,
    written_off_quantity NUMERIC(22, 7) NOT NULL DEFAULT 0,
    unit_cost NUMERIC(19, 6) NOT NULL DEFAULT 0,
    total_value NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
    is_delete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(120),
    updated_by VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_wh_holding_scope ON warehouse_holdings(tenant_id, company_id, material_id);
CREATE INDEX IF NOT EXISTS idx_wh_holding_dept ON warehouse_holdings(tenant_id, company_id, department_id);
CREATE INDEX IF NOT EXISTS idx_wh_holding_emp ON warehouse_holdings(tenant_id, company_id, employee_id);
CREATE INDEX IF NOT EXISTS idx_wh_holding_account ON warehouse_holdings(tenant_id, company_id, account_code);

CREATE TABLE IF NOT EXISTS warehouse_holding_handovers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(80) NOT NULL,
    company_id UUID NOT NULL,
    code VARCHAR(80),
    name TEXT,
    description TEXT,
    handover_number VARCHAR(100) NOT NULL,
    holding_id UUID,
    material_id UUID NOT NULL,
    department_id UUID,
    employee_id UUID,
    quantity NUMERIC(22, 7) NOT NULL,
    unit_cost NUMERIC(19, 6) NOT NULL DEFAULT 0,
    total_value NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    dispatched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at TIMESTAMPTZ,
    rejection_reason VARCHAR(255),
    idempotency_key VARCHAR(160) NOT NULL,
    is_delete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(120),
    updated_by VARCHAR(120),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_wh_handover_idempotency UNIQUE (tenant_id, company_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_wh_handover_scope ON warehouse_holding_handovers(tenant_id, company_id, handover_number);
CREATE INDEX IF NOT EXISTS idx_wh_handover_holding ON warehouse_holding_handovers(holding_id);
