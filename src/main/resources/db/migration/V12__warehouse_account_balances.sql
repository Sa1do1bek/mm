-- Account positions retain their source ownership/use classification. They do not
-- create freely available TMB batches or duplicate the reconciled 10xx opening.
CREATE TABLE warehouse_account_imports (
 id uuid PRIMARY KEY, tenant_id varchar(80) NOT NULL, company_id uuid NOT NULL,
 organization_source_id varchar(32) NOT NULL, snapshot_date date NOT NULL,
 manifest_sha256 varchar(64) NOT NULL, expected_counts jsonb NOT NULL,
 expected_records bigint NOT NULL, accepted_records bigint NOT NULL DEFAULT 0,
 expected_chain varchar(64) NOT NULL, chain_sha256 varchar(64) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'IMPORTING',
 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at timestamp,
 UNIQUE(tenant_id,company_id,organization_source_id,snapshot_date)
);
CREATE TABLE warehouse_account_entries (
 id uuid PRIMARY KEY, tenant_id varchar(80) NOT NULL, company_id uuid NOT NULL,
 import_id uuid NOT NULL REFERENCES warehouse_account_imports(id), source_seq bigint NOT NULL,
 source_key varchar(240) NOT NULL, kind varchar(10) NOT NULL CHECK(kind IN ('BALANCE','MOVEMENT')),
 source_date date NOT NULL, account_source_id varchar(32) NOT NULL,
 account_code varchar(10) NOT NULL, account_name text NOT NULL,
 material_reference_type varchar(8) NOT NULL, material_source_id varchar(32) NOT NULL,
 warehouse_source_id varchar(32) NOT NULL, material_id uuid, warehouse_id uuid,
 material_code text NOT NULL, material_name text NOT NULL,
 warehouse_code text NOT NULL, warehouse_name text NOT NULL,
 source_type varchar(30) NOT NULL, source_material_type text NOT NULL,
 stock_category varchar(40) NOT NULL, uom text NOT NULL, uom_name_ru text NOT NULL,
 quantity numeric(22,7) NOT NULL, amount numeric(19,4) NOT NULL,
 corresponding_account text NOT NULL DEFAULT '—', counterparty text NOT NULL DEFAULT '—',
 document_key text, additional_dimensions jsonb NOT NULL,
 row_sha256 varchar(64) NOT NULL, content text NOT NULL,
 UNIQUE(tenant_id,company_id,source_key), UNIQUE(import_id,source_seq)
);
CREATE TABLE warehouse_account_chunks (
 import_id uuid NOT NULL REFERENCES warehouse_account_imports(id), first_seq bigint NOT NULL,
 row_count integer NOT NULL, payload_sha256 varchar(64) NOT NULL, chain_sha256 varchar(64) NOT NULL,
 PRIMARY KEY(import_id,first_seq)
);
CREATE INDEX warehouse_account_report ON warehouse_account_entries(tenant_id,company_id,kind,source_date,account_code);
CREATE INDEX warehouse_account_material ON warehouse_account_entries(tenant_id,company_id,material_id,kind,source_date);
CREATE INDEX warehouse_account_warehouse ON warehouse_account_entries(tenant_id,company_id,warehouse_id,kind,source_date);
