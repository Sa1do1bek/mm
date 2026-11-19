CREATE TABLE warehouse_report_approvals (
    id uuid PRIMARY KEY,
    tenant_id varchar(255) NOT NULL,
    company_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    period_from date NOT NULL,
    period_to date NOT NULL,
    revision integer NOT NULL,
    approved_at timestamptz NOT NULL DEFAULT now(),
    approved_by varchar(255) NOT NULL,
    snapshot_hash varchar(64) NOT NULL,
    snapshot jsonb NOT NULL,
    CONSTRAINT warehouse_report_approval_period_check CHECK (period_from <= period_to),
    CONSTRAINT warehouse_report_approval_revision_check CHECK (revision > 0),
    CONSTRAINT warehouse_report_approval_revision_unique UNIQUE
        (tenant_id, company_id, warehouse_id, period_from, period_to, revision)
);

CREATE INDEX warehouse_report_approval_latest_idx ON warehouse_report_approvals
    (tenant_id, company_id, period_from, period_to, warehouse_id, revision DESC);
