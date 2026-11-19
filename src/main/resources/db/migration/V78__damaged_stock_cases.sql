CREATE TABLE damaged_stock_cases (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp,
    created_by varchar(120),
    updated_by varchar(120),
    version bigint NOT NULL,
    is_delete boolean NOT NULL DEFAULT false,
    code varchar(80) NOT NULL,
    name text NOT NULL,
    status varchar(50) NOT NULL,
    description text,
    balance_id uuid NOT NULL REFERENCES stock_balances(id),
    warehouse_id uuid NOT NULL REFERENCES warehouses(id),
    condition varchar(20) NOT NULL CHECK (condition IN ('DAMAGED', 'UNUSABLE')),
    quantity numeric(22,7) NOT NULL CHECK (quantity > 0),
    approval_instance_id uuid REFERENCES approval_instances(id),
    movement_id uuid REFERENCES stock_movements(id),
    resolution_note text,
    CONSTRAINT uk_damaged_stock_case_code UNIQUE (tenant_id, company_id, code)
);

CREATE INDEX ix_damaged_stock_cases_queue
    ON damaged_stock_cases (tenant_id, company_id, warehouse_id, status, created_at DESC)
    WHERE NOT is_delete;
