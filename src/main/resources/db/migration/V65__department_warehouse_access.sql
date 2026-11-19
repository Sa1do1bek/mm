CREATE TABLE department_warehouse_access (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES companies(id),
    department_id uuid NOT NULL REFERENCES departments(id),
    warehouse_id uuid NOT NULL REFERENCES warehouses(id),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by varchar(120),
    CONSTRAINT uk_department_warehouse_access
        UNIQUE (tenant_id, company_id, department_id, warehouse_id)
);

CREATE INDEX idx_department_warehouse_access_department
    ON department_warehouse_access (tenant_id, company_id, department_id);

CREATE INDEX idx_department_warehouse_access_warehouse
    ON department_warehouse_access (tenant_id, company_id, warehouse_id);

INSERT INTO department_warehouse_access
    (tenant_id, company_id, department_id, warehouse_id, created_by)
SELECT DISTINCT e.tenant_id, e.company_id, e.department_id, e.warehouse_id, 'V65_BACKFILL'
FROM employees e
JOIN departments d
  ON d.id = e.department_id
 AND d.tenant_id = e.tenant_id
 AND d.company_id = e.company_id
 AND d.is_delete = false
JOIN warehouses w
  ON w.id = e.warehouse_id
 AND w.tenant_id = e.tenant_id
 AND w.company_id = e.company_id
 AND w.is_delete = false
WHERE e.is_delete = false
  AND e.department_id IS NOT NULL
  AND e.warehouse_id IS NOT NULL
ON CONFLICT (tenant_id, company_id, department_id, warehouse_id) DO NOTHING;
