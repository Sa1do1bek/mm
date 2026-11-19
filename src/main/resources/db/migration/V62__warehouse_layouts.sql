CREATE TABLE warehouse_layouts (
    warehouse_id uuid PRIMARY KEY REFERENCES warehouses(id),
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES companies(id),
    version bigint NOT NULL,
    layout jsonb NOT NULL,
    updated_at timestamp NOT NULL,
    updated_by varchar(120)
);
CREATE INDEX idx_warehouse_layout_scope ON warehouse_layouts (tenant_id, company_id, warehouse_id);
