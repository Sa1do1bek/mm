-- Department types are company-owned reference data. Older validation looked up
-- only by tenant and code, so valid rows in two companies caused a non-unique
-- result while saving a department.

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY tenant_id, company_id, upper(trim(code))
               ORDER BY system_type DESC, created_at ASC, id ASC
           ) AS duplicate_rank
    FROM department_types
    WHERE is_delete = false
      AND code IS NOT NULL
      AND trim(code) <> ''
)
UPDATE department_types department_type
SET is_delete = true,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 'V66_DEDUPLICATION'
FROM ranked
WHERE department_type.id = ranked.id
  AND ranked.duplicate_rank > 1;

CREATE UNIQUE INDEX uk_department_types_company_code_active
    ON department_types (tenant_id, company_id, upper(trim(code)))
    WHERE is_delete = false AND code IS NOT NULL AND trim(code) <> '';
