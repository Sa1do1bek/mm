CREATE TABLE approval_operation_requests (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp,
    created_by varchar(120),
    updated_by varchar(120),
    version bigint NOT NULL,
    is_delete boolean NOT NULL DEFAULT false,
    code varchar(80),
    name text NOT NULL,
    status varchar(50),
    description text,
    module_key varchar(80) NOT NULL,
    target_entity_id uuid NOT NULL,
    target_version bigint,
    requester_username varchar(120) NOT NULL,
    payload_json text NOT NULL,
    payload_hash varchar(64) NOT NULL,
    idempotency_key varchar(160) NOT NULL,
    approval_instance_id uuid REFERENCES approval_instances(id),
    failure_reason text
);

CREATE UNIQUE INDEX uk_approval_operation_idempotency
    ON approval_operation_requests (tenant_id, company_id, requester_username, idempotency_key)
    WHERE is_delete = false;

CREATE UNIQUE INDEX uk_approval_operation_pending_target
    ON approval_operation_requests (tenant_id, company_id, module_key, target_entity_id)
    WHERE is_delete = false AND status IN ('DRAFT', 'PENDING', 'RETURNED', 'APPROVED_NOT_APPLIED', 'APPLY_FAILED');

CREATE INDEX idx_approval_operation_target
    ON approval_operation_requests (tenant_id, company_id, target_entity_id, created_at DESC);
