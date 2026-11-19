ALTER TABLE inventory_cost_layers ADD COLUMN authoritative_value boolean NOT NULL DEFAULT false;

CREATE TABLE exact_opening_imports (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES companies(id),
    source_manifest_sha256 varchar(64) NOT NULL,
    prepared_manifest_sha256 varchar(64) NOT NULL,
    source_organization_id varchar(32) NOT NULL,
    source_evidence text NOT NULL,
    posting_date date NOT NULL,
    expected_rows integer NOT NULL CHECK (expected_rows > 0),
    expected_quantity numeric(22,7) NOT NULL CHECK (expected_quantity > 0),
    expected_value numeric(19,4) NOT NULL CHECK (expected_value >= 0),
    accepted_rows integer NOT NULL DEFAULT 0 CHECK (accepted_rows >= 0),
    accepted_quantity numeric(22,7) NOT NULL DEFAULT 0 CHECK (accepted_quantity >= 0),
    accepted_value numeric(19,4) NOT NULL DEFAULT 0 CHECK (accepted_value >= 0),
    status varchar(20) NOT NULL CHECK (status IN ('IMPORTING','COMPLETED')),
    created_at timestamp NOT NULL,
    completed_at timestamp,
    CONSTRAINT uk_exact_opening_company UNIQUE (tenant_id, company_id)
);
CREATE TABLE exact_opening_rows (
    id uuid PRIMARY KEY,
    import_id uuid NOT NULL REFERENCES exact_opening_imports(id),
    source_row_id varchar(220) NOT NULL,
    account_source_id varchar(32) NOT NULL,
    account_code varchar(4) NOT NULL CHECK (account_code LIKE '10%'),
    material_source_id varchar(32) NOT NULL,
    warehouse_source_id varchar(32) NOT NULL,
    movement_id uuid NOT NULL UNIQUE REFERENCES stock_movements(id),
    quantity numeric(22,7) NOT NULL CHECK (quantity > 0),
    total_value numeric(19,4) NOT NULL CHECK (total_value >= 0),
    CONSTRAINT uk_exact_opening_source_row UNIQUE (import_id, source_row_id)
);
