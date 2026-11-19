-- Migration V17: Historical-Origin Returns and Reversal tracking
-- 1. Make issue_request_id nullable in return_documents to support HISTORICAL_1C returns without fake MM issue
ALTER TABLE return_documents ALTER COLUMN issue_request_id DROP NOT NULL;

-- 2. Add origin context fields to return_documents
ALTER TABLE return_documents ADD COLUMN IF NOT EXISTS origin_type VARCHAR(40) NOT NULL DEFAULT 'NATIVE_ISSUE';
ALTER TABLE return_documents ADD COLUMN IF NOT EXISTS historical_document_number VARCHAR(100);
ALTER TABLE return_documents ADD COLUMN IF NOT EXISTS historical_document_date DATE;
ALTER TABLE return_documents ADD COLUMN IF NOT EXISTS historical_source_system VARCHAR(50);
ALTER TABLE return_documents ADD COLUMN IF NOT EXISTS owner_id VARCHAR(64);

-- 3. Make issue_request_line_id nullable in return_document_lines
ALTER TABLE return_document_lines ALTER COLUMN issue_request_line_id DROP NOT NULL;

-- 4. Add serial number and owner tracking to return_document_lines
ALTER TABLE return_document_lines ADD COLUMN IF NOT EXISTS serial_number VARCHAR(100);
ALTER TABLE return_document_lines ADD COLUMN IF NOT EXISTS owner_id VARCHAR(64);

-- 5. Table to link return origins and track returnable balance
CREATE TABLE IF NOT EXISTS return_origin_links (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    company_id UUID NOT NULL,
    return_document_id UUID NOT NULL,
    return_line_id UUID NOT NULL,
    origin_type VARCHAR(40) NOT NULL,
    origin_reference VARCHAR(120) NOT NULL,
    material_id UUID NOT NULL,
    returned_quantity NUMERIC(22, 7) NOT NULL,
    returned_unit_cost NUMERIC(19, 6) NOT NULL,
    returned_total_value NUMERIC(19, 4) NOT NULL,
    owner_id VARCHAR(64),
    serial_number VARCHAR(100),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_rol_tenant_company_origin ON return_origin_links(tenant_id, company_id, origin_reference);
CREATE INDEX IF NOT EXISTS idx_rol_return_doc ON return_origin_links(return_document_id);
