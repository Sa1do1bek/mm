ALTER TABLE kimyosanoat_event_inbox
    ADD COLUMN attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN review_reason varchar(80),
    ADD COLUMN review_detail text,
    ADD COLUMN movement_document_id uuid,
    ADD COLUMN processed_at timestamptz;

CREATE INDEX ix_kimyosanoat_event_due
    ON kimyosanoat_event_inbox (next_attempt_at, received_at)
    WHERE processing_state IN ('RECEIVED', 'RETRY', 'PROCESSING');
