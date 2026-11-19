-- Backfill only a procedure's explicitly referenced campaign, within its tenant and company.
-- Conflicting reverse links, deleted records and existing plan assignments are left unchanged.
UPDATE procurement_procedures p
SET procurement_plan_id = (
    SELECT c.procurement_plan_id FROM procurement_campaigns c
    JOIN procurement_plans pl ON pl.id = c.procurement_plan_id
        AND pl.tenant_id = c.tenant_id AND pl.company_id = c.company_id AND pl.is_delete = false
    WHERE c.id = p.campaign_id AND c.tenant_id = p.tenant_id AND c.company_id = p.company_id
      AND c.is_delete = false AND c.procurement_plan_id IS NOT NULL
      AND (c.procurement_procedure_id IS NULL OR c.procurement_procedure_id = p.id)
)
WHERE p.is_delete = false AND p.procurement_plan_id IS NULL AND p.campaign_id IS NOT NULL
  AND EXISTS (
    SELECT 1 FROM procurement_campaigns c
    JOIN procurement_plans pl ON pl.id = c.procurement_plan_id
        AND pl.tenant_id = c.tenant_id AND pl.company_id = c.company_id AND pl.is_delete = false
    WHERE c.id = p.campaign_id AND c.tenant_id = p.tenant_id AND c.company_id = p.company_id
      AND c.is_delete = false AND c.procurement_plan_id IS NOT NULL
      AND (c.procurement_procedure_id IS NULL OR c.procurement_procedure_id = p.id)
  );
