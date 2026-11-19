-- Equipment and animals remain TMB for stock, valuation and procurement.
ALTER TABLE materials ADD COLUMN material_subtype varchar(30);
UPDATE materials SET material_subtype = 'MATERIAL' WHERE material_type = 'TMB';
ALTER TABLE materials ADD CONSTRAINT materials_subtype_business_type_check CHECK (
    material_subtype IS NULL OR
    (material_type = 'TMB' AND material_subtype IN ('MATERIAL', 'EQUIPMENT', 'ANIMALS'))
);
CREATE INDEX materials_subtype_scope ON materials (tenant_id, company_id, material_subtype);

-- Restore explicit kinds from the immutable Catalog54 evidence of the authorized import.
-- Names are never used to infer classification; validate both source ID and enum reference.
DO $$
DECLARE
    item record;
    evidence jsonb;
    source_id text;
    subtype text;
    evidence_marker constant text := E'\n1C source evidence: ';
BEGIN
    FOR item IN
        SELECT m.id, m.description
        FROM materials m
        JOIN companies c ON c.id = m.company_id AND c.tenant_id = m.tenant_id
        WHERE m.tenant_id = 'default' AND c.code = 'NAVOIYAZOT-1C-20261008'
          AND m.material_type = 'TMB'
          AND strpos(m.description, evidence_marker) > 0
          AND (m.description LIKE '%aa364d2c36cb56724241db6745169071%'
               OR m.description LIKE '%8a7701486f0764b5414e5cd892150a1f%')
    LOOP
        source_id := substring(item.description FROM '(?n)^Catalog54:([0-9a-f]{32})$');
        IF source_id IS NULL THEN CONTINUE; END IF;
        BEGIN
            -- The catalog importer can append its idempotency marker after the JSON line.
            evidence := split_part(substring(item.description FROM
                strpos(item.description, evidence_marker) + length(evidence_marker)), E'\n', 1)::jsonb;
        EXCEPTION WHEN invalid_text_representation THEN
            CONTINUE;
        END;
        IF evidence->>'source_id' IS DISTINCT FROM source_id THEN CONTINUE; END IF;
        subtype := CASE
            WHEN evidence->>'item_kind_source_id' = 'aa364d2c36cb56724241db6745169071'
                 AND evidence->>'item_kind_name' = 'Оборудование' THEN 'EQUIPMENT'
            WHEN evidence->>'item_kind_source_id' = '8a7701486f0764b5414e5cd892150a1f'
                 AND evidence->>'item_kind_name' = 'Животные' THEN 'ANIMALS'
            ELSE NULL
        END;
        IF subtype IS NOT NULL THEN
            UPDATE materials SET material_subtype = subtype, version = version + 1,
                updated_at = CURRENT_TIMESTAMP, updated_by = 'migration:1c-material-subtypes'
            WHERE id = item.id;
        END IF;
    END LOOP;
END $$;
