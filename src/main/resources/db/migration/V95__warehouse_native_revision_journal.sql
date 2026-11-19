-- Information-only native register mirror. No operational balance or FIFO mutation.
CREATE TABLE one_c_warehouse_documents (
 id uuid PRIMARY KEY,tenant_id varchar(80) NOT NULL,company_id uuid NOT NULL,
 source_instance_id varchar(160) NOT NULL,organization_id varchar(160) NOT NULL,
 document_type varchar(240) NOT NULL,document_id varchar(160) NOT NULL,
 revision bigint NOT NULL CHECK(revision>0),state varchar(16) NOT NULL CHECK(state IN('ACTIVE','EMPTY','DELETED')),
 document_date date NOT NULL,document_number text,origin varchar(80) NOT NULL,correlation_id text,
 row_count integer NOT NULL,coverage varchar(32) NOT NULL,updated_at timestamp NOT NULL DEFAULT current_timestamp,
 UNIQUE(tenant_id,company_id,source_instance_id,organization_id,document_type,document_id)
);
CREATE TABLE one_c_warehouse_revisions (
 id uuid PRIMARY KEY,document_head_id uuid NOT NULL REFERENCES one_c_warehouse_documents(id),
 tenant_id varchar(80) NOT NULL,company_id uuid NOT NULL,event_id uuid NOT NULL,
 revision bigint NOT NULL CHECK(revision>0),state varchar(16) NOT NULL,payload_hash varchar(64) NOT NULL,
 payload jsonb NOT NULL,received_at timestamp NOT NULL DEFAULT current_timestamp,
 UNIQUE(document_head_id,revision),UNIQUE(tenant_id,company_id,event_id)
);
CREATE TABLE one_c_warehouse_current_rows (
 document_head_id uuid NOT NULL REFERENCES one_c_warehouse_documents(id),row_key varchar(240) NOT NULL,
 period timestamp NOT NULL,side varchar(2) NOT NULL,account_id varchar(160) NOT NULL,account_code varchar(80) NOT NULL,
 material_id varchar(160),material_name text,warehouse_id varchar(160),warehouse_name text,
 quantity numeric NOT NULL,amount numeric NOT NULL,currency varchar(20) NOT NULL,analytics jsonb NOT NULL,
 mapped_material_id uuid,mapped_warehouse_id uuid,
 PRIMARY KEY(document_head_id,row_key)
);
CREATE INDEX one_c_wh_doc_scope ON one_c_warehouse_documents(tenant_id,company_id,document_date DESC,id);
CREATE INDEX one_c_wh_rows_position ON one_c_warehouse_current_rows(material_id,warehouse_id,account_id);
CREATE TABLE one_c_warehouse_history_runs (
 id uuid PRIMARY KEY,tenant_id varchar(80) NOT NULL,company_id uuid NOT NULL,
 source_instance_id varchar(160) NOT NULL,organization_id varchar(160) NOT NULL,
 date_from date NOT NULL,date_to date NOT NULL,page_size integer NOT NULL CHECK(page_size BETWEEN 1 AND 200),
 idempotency_key varchar(160) NOT NULL,request_hash varchar(64) NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN('PENDING','LOADING','COMPLETE')),
 cursor text NOT NULL DEFAULT '',snapshot_sequence bigint,expected_document_count bigint CHECK(expected_document_count>=0),
 page_count bigint NOT NULL DEFAULT 0,document_count bigint NOT NULL DEFAULT 0,row_count bigint NOT NULL DEFAULT 0,
 created_at timestamp NOT NULL DEFAULT current_timestamp,completed_at timestamp,
 UNIQUE(tenant_id,company_id,idempotency_key),CHECK(date_to>=date_from)
);
CREATE TABLE one_c_warehouse_history_pages (
 run_id uuid NOT NULL REFERENCES one_c_warehouse_history_runs(id),page_id varchar(160) NOT NULL,
 cursor text NOT NULL,next_cursor text NOT NULL,payload_hash varchar(64) NOT NULL,
 document_count integer NOT NULL,row_count integer NOT NULL,completed boolean NOT NULL,payload jsonb NOT NULL,
 received_at timestamp NOT NULL DEFAULT current_timestamp,
 PRIMARY KEY(run_id,page_id),UNIQUE(run_id,cursor)
);
