-- V23: Historical Custody Activation (1C Schet 002).
-- Records idempotent activation of historical 1C custody rows, source row hashes, and reference non-owned amounts.

CREATE TABLE IF NOT EXISTS warehouse_custody_activations (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    company_id UUID NOT NULL,
    source_reference_id VARCHAR(160) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    material_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    account_code VARCHAR(40) NOT NULL DEFAULT '002',
    quantity NUMERIC(22, 7) NOT NULL,
    reference_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency_code VARCHAR(3) NOT NULL DEFAULT 'UZS',
    activation_date DATE NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'COMMITTED',
    receipt_json TEXT,
    code VARCHAR(80),
    name TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    is_delete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(120),
    updated_by VARCHAR(120),
    CONSTRAINT uk_custody_activation_source UNIQUE (tenant_id, company_id, source_reference_id)
);

CREATE INDEX IF NOT EXISTS idx_custody_activations_lookup
    ON warehouse_custody_activations (tenant_id, company_id, material_id, warehouse_id, owner_id);
