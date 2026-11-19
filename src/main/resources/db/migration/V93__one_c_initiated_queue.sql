-- Queue tasks preserve existing outbox UUID/payload; confirmation is a separate durable phase.
CREATE TABLE one_c_pull_task (
 id uuid PRIMARY KEY, tenant_id varchar(100) NOT NULL, company_id uuid NOT NULL,
 task_type varchar(16) NOT NULL CHECK(task_type IN ('DOCUMENT','CONFIRM','BALANCES')),
 envelope_id uuid REFERENCES erp_exchange_envelope(id),
 event_id uuid NOT NULL, payload text NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','RETRY','COMPLETE')),
 worker_id varchar(128), lease_token uuid, lease_expires_at timestamptz,
 attempt_count integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 result text, result_hash varchar(64), last_error varchar(500),
 query_type varchar(16) CHECK(query_type IN ('PERIOD','BALANCE')), period_type varchar(8),
 requested_by varchar(255), report_result text,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at timestamptz,
 UNIQUE(envelope_id,task_type)
);
CREATE UNIQUE INDEX uq_one_c_pull_active_scope ON one_c_pull_task(tenant_id,company_id) WHERE state='LEASED';
CREATE INDEX ix_one_c_pull_due ON one_c_pull_task(tenant_id,company_id,next_attempt_at,created_at) WHERE state IN ('PENDING','RETRY');
CREATE INDEX ix_one_c_pull_evidence ON one_c_pull_task(tenant_id,company_id,completed_at DESC) WHERE query_type IS NOT NULL;
ALTER TABLE erp_period_review_run ADD COLUMN native_evidence text, ADD COLUMN native_task_id uuid REFERENCES one_c_pull_task(id);
