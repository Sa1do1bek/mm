-- A supplemental 1C opening is separate from the completed original 10xx opening.
CREATE TABLE finished_goods_opening_runs (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    snapshot_date date NOT NULL,
    idempotency_key varchar(160) NOT NULL,
    preview_sha256 varchar(64) NOT NULL,
    posted_count integer NOT NULL,
    created_by varchar(120) NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_finished_goods_opening_run UNIQUE (tenant_id, company_id, idempotency_key)
);

CREATE TABLE finished_goods_opening_positions (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    material_id uuid NOT NULL,
    snapshot_date date NOT NULL,
    source_sha256 varchar(64) NOT NULL,
    source_evidence jsonb NOT NULL,
    movement_id uuid NOT NULL REFERENCES stock_movements(id),
    run_id uuid NOT NULL REFERENCES finished_goods_opening_runs(id),
    CONSTRAINT uk_finished_goods_opening_position UNIQUE
        (tenant_id, company_id, snapshot_date, warehouse_id, material_id),
    CONSTRAINT uk_finished_goods_opening_movement UNIQUE (movement_id)
);
