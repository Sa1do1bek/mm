-- Stop for an explicit data-resolution decision if existing active codes collide after normalization.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.materials
        WHERE is_delete = false AND code IS NOT NULL AND btrim(code) <> ''
        GROUP BY tenant_id, company_id, upper(btrim(code))
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Active material codes contain duplicates after trim/uppercase normalization; resolve them before V72';
    END IF;
END $$;

CREATE UNIQUE INDEX uk_materials_active_normalized_company_code
    ON public.materials (tenant_id, company_id, upper(btrim(code)))
    WHERE is_delete = false AND code IS NOT NULL AND btrim(code) <> '';
