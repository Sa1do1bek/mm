CREATE TABLE kimyosanoat_waybill_inbox (
    receipt_id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    payload_sha256 varchar(64) NOT NULL,
    payload_bytes bytea NOT NULL,
    transfer_id bigint CHECK (transfer_id > 0),
    warehouse_1c_id uuid,
    processing_state varchar(24) NOT NULL,
    review_reason varchar(80),
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_kimyosanoat_waybill_payload UNIQUE (tenant_id, company_id, payload_sha256),
    CONSTRAINT ck_kimyosanoat_waybill_source_pair CHECK (
        (transfer_id IS NULL AND warehouse_1c_id IS NULL)
        OR (transfer_id IS NOT NULL AND warehouse_1c_id IS NOT NULL)
    )
);

CREATE INDEX ix_kimyosanoat_waybill_review
    ON kimyosanoat_waybill_inbox (tenant_id, company_id, received_at DESC)
    WHERE processing_state = 'REVIEW_REQUIRED';
