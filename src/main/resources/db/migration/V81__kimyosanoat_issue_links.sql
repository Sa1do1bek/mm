CREATE TABLE kimyosanoat_issue_links (
    source_transfer_id bigint PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    material_id uuid NOT NULL,
    movement_document_id uuid,
    source_fingerprint varchar(64) NOT NULL,
    source_snapshot jsonb NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by varchar(120) NOT NULL
);
CREATE INDEX ix_kimyo_issue_scope ON kimyosanoat_issue_links (tenant_id, company_id, warehouse_id);
