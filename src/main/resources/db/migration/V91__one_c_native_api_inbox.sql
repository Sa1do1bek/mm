CREATE TABLE one_c_api_inbox (
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    event_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_kind varchar(20) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    applied_at timestamp NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (tenant_id, company_id, event_id),
    UNIQUE (tenant_id, company_id, document_kind, document_id)
);
