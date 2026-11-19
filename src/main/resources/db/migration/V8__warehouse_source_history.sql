-- Historical evidence is intentionally separate from operational stock and workflows.
CREATE TABLE warehouse_history_runs (
 id uuid PRIMARY KEY, tenant_id varchar(80) NOT NULL, company_id uuid NOT NULL,
 manifest_sha256 varchar(64) NOT NULL, organization_source_id varchar(32) NOT NULL,
 expected_counts text NOT NULL, expected_records bigint NOT NULL,
 expected_chain varchar(64) NOT NULL, accepted_records bigint NOT NULL DEFAULT 0,
 chain_sha256 varchar(64) NOT NULL, status varchar(20) NOT NULL,
 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at timestamp,
 created_by varchar(120), UNIQUE(tenant_id,company_id,manifest_sha256)
);
CREATE TABLE warehouse_history_records (
 id uuid PRIMARY KEY, tenant_id varchar(80) NOT NULL, company_id uuid NOT NULL,
 import_id uuid NOT NULL REFERENCES warehouse_history_runs(id), source_seq bigint NOT NULL,
 kind varchar(20) NOT NULL, source_key varchar(180) NOT NULL, source_table varchar(80) NOT NULL,
 document_key varchar(180), document_type varchar(240), document_date timestamp,
 document_number varchar(240), posted boolean, marked boolean,
 material_id uuid, warehouse_id uuid, target_warehouse_id uuid,
 quantity text, book_value text, direction varchar(20), row_sha256 varchar(64) NOT NULL,
 content text NOT NULL,
 UNIQUE(tenant_id,company_id,source_key), UNIQUE(import_id,source_seq)
);
CREATE INDEX warehouse_history_kind_date ON warehouse_history_records(tenant_id,company_id,kind,document_date,id);
CREATE INDEX warehouse_history_document ON warehouse_history_records(tenant_id,company_id,document_key,kind);
CREATE INDEX warehouse_history_material ON warehouse_history_records(tenant_id,company_id,material_id,kind,document_date);
CREATE INDEX warehouse_history_warehouse ON warehouse_history_records(tenant_id,company_id,warehouse_id,kind,document_date);
CREATE INDEX warehouse_history_target ON warehouse_history_records(tenant_id,company_id,target_warehouse_id,kind,document_date);
CREATE TABLE warehouse_history_chunks (
 import_id uuid NOT NULL REFERENCES warehouse_history_runs(id), first_seq bigint NOT NULL,
 row_count integer NOT NULL, payload_sha256 varchar(64) NOT NULL,
 chain_sha256 varchar(64) NOT NULL, PRIMARY KEY(import_id,first_seq)
);
