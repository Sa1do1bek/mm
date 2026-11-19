-- V21: Custody Agreements (Schet 002) and Counterparty Bindings.
-- Manages legal owner-custodian agreements, settlement policies, and authorization periods.

CREATE TABLE IF NOT EXISTS warehouse_custody_agreements (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(80) NOT NULL,
    company_id UUID NOT NULL,
    agreement_number VARCHAR(120) NOT NULL,
    owner_id UUID NOT NULL,
    counterparty_name VARCHAR(255) NOT NULL,
    valid_from DATE NOT NULL,
    valid_through DATE,
    agreement_status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE' CHECK (agreement_status IN ('DRAFT','ACTIVE','SUSPENDED','TERMINATED')),
    settlement_policy VARCHAR(60) NOT NULL DEFAULT 'STORAGE_FEE',
    notes TEXT,
    code VARCHAR(80),
    name TEXT,
    status VARCHAR(50),
    version BIGINT NOT NULL DEFAULT 0,
    is_delete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(120),
    updated_by VARCHAR(120),
    CONSTRAINT uk_custody_agreement_scope UNIQUE (tenant_id, company_id, agreement_number)
);

CREATE INDEX IF NOT EXISTS idx_custody_agreements_owner
    ON warehouse_custody_agreements (tenant_id, company_id, owner_id, agreement_status);
