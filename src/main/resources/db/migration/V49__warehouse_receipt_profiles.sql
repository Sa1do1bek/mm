CREATE TABLE warehouse_receipt_profiles (
    warehouse_id uuid PRIMARY KEY REFERENCES warehouses(id),
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES companies(id),
    version bigint NOT NULL,
    purpose varchar(2000),
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    created_by varchar(120),
    updated_by varchar(120)
);
CREATE INDEX idx_warehouse_receipt_profile_scope ON warehouse_receipt_profiles (tenant_id, company_id, warehouse_id);
CREATE TABLE warehouse_receipt_profile_rules (
    warehouse_id uuid NOT NULL REFERENCES warehouse_receipt_profiles(warehouse_id),
    material_group_id uuid NOT NULL REFERENCES material_groups(id),
    effect varchar(10) NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
    include_descendants boolean NOT NULL,
    PRIMARY KEY (warehouse_id, material_group_id)
);
