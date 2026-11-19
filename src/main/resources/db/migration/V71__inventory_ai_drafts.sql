CREATE TABLE inventory_ai_drafts (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    owner_username varchar(120) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    inventory_document_id uuid REFERENCES inventory_documents(id),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamp NOT NULL,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_inventory_ai_drafts_owner ON inventory_ai_drafts(tenant_id, company_id, owner_username, status);
CREATE INDEX idx_inventory_ai_drafts_expiry ON inventory_ai_drafts(expires_at) WHERE status = 'OPEN';
