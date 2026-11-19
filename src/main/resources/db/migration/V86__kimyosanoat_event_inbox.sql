CREATE TABLE kimyosanoat_event_inbox (
    event_id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    transfer_id bigint NOT NULL CHECK (transfer_id > 0),
    warehouse_1c_id uuid NOT NULL,
    event_type varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    payload_sha256 varchar(64) NOT NULL,
    processing_state varchar(24) NOT NULL DEFAULT 'RECEIVED',
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_kimyosanoat_event_inbox_pending
    ON kimyosanoat_event_inbox (processing_state, received_at);
CREATE INDEX ix_kimyosanoat_event_inbox_transfer
    ON kimyosanoat_event_inbox (tenant_id, company_id, transfer_id);
