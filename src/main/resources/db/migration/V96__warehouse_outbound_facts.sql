-- Committed MM facts captured atomically with source stock transactions. No source data mutation.
CREATE TABLE one_c_warehouse_outbound (
 operation_id uuid PRIMARY KEY,tenant_id varchar(80) NOT NULL,company_id uuid NOT NULL,
 source_instance_id varchar(160) NOT NULL,organization_id varchar(160) NOT NULL,
 source_key varchar(240) NOT NULL,source_engine varchar(40) NOT NULL,source_hash varchar(64) NOT NULL,
 operation_type varchar(260) NOT NULL,resource_id uuid NOT NULL,posting_date date NOT NULL,
 revision bigint NOT NULL CHECK(revision>0),policy varchar(40) NOT NULL,
 eligible_context varchar(40),state varchar(24) NOT NULL DEFAULT 'PENDING',pending_reason text,
 legacy_candidates jsonb NOT NULL DEFAULT '[]',native_document_id uuid,
 lease_token uuid,lease_worker varchar(160),lease_until timestamptz,attempts integer NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT current_timestamp,updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 UNIQUE(tenant_id,company_id,source_key),CHECK(source_hash ~ '^[0-9a-f]{64}$'),
 CHECK(policy IN('LEGACY_LINKED','REGISTRY_ONLY','NATIVE_MAPPING_REQUIRED','NATIVE_CORRECTION')),
 CHECK(state IN('PENDING','LEASED','OBSERVED','REVIEW_REQUIRED','APPLIED','FAILED'))
);
CREATE TABLE one_c_warehouse_outbound_versions (
 operation_id uuid NOT NULL REFERENCES one_c_warehouse_outbound(operation_id),revision bigint NOT NULL,
 event_id uuid NOT NULL UNIQUE,payload_json text NOT NULL,payload_hash varchar(64) NOT NULL,
 state varchar(24) NOT NULL DEFAULT 'PENDING',result_json text,result_hash varchar(64),
 approval_key varchar(160),approval_hash varchar(64),approved_by varchar(160),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,result_at timestamptz,
 PRIMARY KEY(operation_id,revision),UNIQUE(operation_id,approval_key),
 CHECK(payload_hash ~ '^[0-9a-f]{64}$'),CHECK(revision>0)
);
CREATE INDEX one_c_wh_out_queue ON one_c_warehouse_outbound(tenant_id,company_id,state,created_at,operation_id);
CREATE INDEX one_c_wh_out_source ON one_c_warehouse_outbound(tenant_id,company_id,source_engine,posting_date);
