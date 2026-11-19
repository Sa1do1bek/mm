CREATE TABLE erp_period_review_run (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    period_type varchar(8) NOT NULL CHECK (period_type IN ('DAY','MONTH')),
    period_start date NOT NULL,
    period_end date NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT current_timestamp,
    created_by varchar(240) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    report jsonb NOT NULL,
    review_version bigint NOT NULL DEFAULT 0,
    review_state varchar(24) NOT NULL DEFAULT 'UNREVIEWED',
    review_comment varchar(2000),
    reviewed_by varchar(240),
    reviewed_at timestamptz,
    CHECK (period_end >= period_start),
    CHECK (review_state IN ('UNREVIEWED','REVIEWED','NEEDS_ATTENTION'))
);
CREATE INDEX idx_erp_period_review_scope ON erp_period_review_run(tenant_id,company_id,period_start DESC,captured_at DESC);
CREATE TABLE erp_period_review_audit (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES erp_period_review_run(id),
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    review_version bigint NOT NULL,
    decision varchar(24) NOT NULL,
    comment varchar(2000) NOT NULL,
    actor varchar(240) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (run_id, review_version)
);
