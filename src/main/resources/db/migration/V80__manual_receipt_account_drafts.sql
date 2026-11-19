CREATE TABLE manual_receipt_account_drafts (
    id uuid PRIMARY KEY,
    tenant_id varchar(100) NOT NULL,
    company_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    warehouse_id uuid NOT NULL REFERENCES warehouses(id),
    material_id uuid NOT NULL REFERENCES materials(id),
    movement_date date NOT NULL,
    purpose varchar(500) NOT NULL,
    quantity numeric(19,7) NOT NULL CHECK (quantity > 0),
    unit_cost numeric(19,4) NOT NULL CHECK (unit_cost >= 0),
    revision integer NOT NULL DEFAULT 1,
    status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','POSTED')),
    posted_movement_id uuid UNIQUE,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_manual_receipt_account_drafts_owner
    ON manual_receipt_account_drafts(tenant_id, company_id, owner_id, status);
