-- A historical request can authorize one reviewed operational draft; it never posts stock itself.
CREATE TABLE warehouse_issue_source_links (
    source_document_id uuid PRIMARY KEY REFERENCES warehouse_history_records(id),
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    transfer_document_id uuid NOT NULL UNIQUE REFERENCES transfer_documents(id),
    source_fingerprint varchar(64) NOT NULL,
    reviewed_by varchar(120) NOT NULL,
    reviewed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    review_note varchar(2000) NOT NULL
);
CREATE INDEX warehouse_issue_source_scope ON warehouse_issue_source_links(tenant_id, company_id);

CREATE INDEX warehouse_history_issue_documents ON warehouse_history_records
    (tenant_id, company_id, warehouse_id, document_date DESC, id)
    WHERE kind='DOCUMENT' AND source_table IN ('_document4470','_document199');

-- Detail-time reconciliation of a requirement with other posted source documents.
CREATE INDEX warehouse_history_requirement_lookup ON warehouse_history_records
    (tenant_id, company_id, (coalesce(nullif(trim(content::jsonb #>> '{data,_fld4527}'),''),
        nullif(trim(content::jsonb #>> '{data,_fld5253}'),''),
        nullif(trim(content::jsonb #>> '{data,_fld13862}'),''))))
    WHERE kind='LINE' AND source_table IN ('_document4470_vt4512','_document162_vt2001','_document182_vt2509');
