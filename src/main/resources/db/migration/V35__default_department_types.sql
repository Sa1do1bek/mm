-- The default company needs its department type dictionary before HR sync or
-- demand planning can validate organization records.
INSERT INTO department_types (
    id, tenant_id, company_id, created_at, version, is_delete,
    code, name, status, level_code, hierarchy_level, system_type
)
SELECT gen_random_uuid(), 'default', '00000000-0000-0000-0000-000000000001'::uuid,
       now(), 0, false, seed.code, seed.name, 'ACTIVE', seed.level_code,
       seed.hierarchy_level, true
FROM (VALUES
    ('COMPANY', 'Company', 'COMPANY', 0),
    ('BRANCH', 'Branch', 'BRANCH', 1),
    ('DIRECTORATE', 'Directorate', 'DIRECTORATE', 1),
    ('ADMINISTRATIVE', 'Administrative unit', 'ADMINISTRATIVE', 2),
    ('FUNCTIONAL', 'Functional unit', 'FUNCTIONAL', 2),
    ('PRODUCTION', 'Production unit', 'PRODUCTION', 2),
    ('COMMERCIAL', 'Commercial unit', 'COMMERCIAL', 2),
    ('LOGISTICS', 'Logistics unit', 'LOGISTICS', 2),
    ('TECHNICAL', 'Technical unit', 'TECHNICAL', 2),
    ('DEPARTMENT', 'Department', 'DEPARTMENT', 3),
    ('SECTION', 'Section', 'SECTION', 4)
) AS seed(code, name, level_code, hierarchy_level)
WHERE NOT EXISTS (
    SELECT 1 FROM department_types existing
    WHERE existing.tenant_id = 'default'
      AND existing.company_id = '00000000-0000-0000-0000-000000000001'::uuid
      AND existing.code = seed.code
      AND existing.is_delete = false
);
