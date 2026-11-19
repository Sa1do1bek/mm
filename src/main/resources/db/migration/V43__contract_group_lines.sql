ALTER TABLE public.contract_specifications
    ADD COLUMN material_group_id uuid,
    ADD COLUMN unit_of_measure_id uuid;

ALTER TABLE public.contract_specifications
    ADD CONSTRAINT ck_contract_specification_material_or_group
        CHECK (material_id IS NULL OR material_group_id IS NULL),
    ADD CONSTRAINT ck_contract_specification_group_material_line
        CHECK (material_group_id IS NULL OR line_type = 'MATERIAL'),
    ADD CONSTRAINT fk_contract_specification_material_group
        FOREIGN KEY (material_group_id) REFERENCES public.material_groups(id) NOT VALID,
    ADD CONSTRAINT fk_contract_specification_unit
        FOREIGN KEY (unit_of_measure_id) REFERENCES public.unit_of_measures(id) NOT VALID;

ALTER TABLE public.stock_movements ADD COLUMN contract_specification_id uuid;
ALTER TABLE public.stock_movements
    ADD CONSTRAINT fk_stock_movement_contract_specification
        FOREIGN KEY (contract_specification_id) REFERENCES public.contract_specifications(id) NOT VALID;

CREATE INDEX idx_contract_specification_group
    ON public.contract_specifications (tenant_id, company_id, contract_id, material_group_id)
    WHERE is_delete = false AND material_group_id IS NOT NULL;
CREATE INDEX idx_stock_movement_contract_specification
    ON public.stock_movements (tenant_id, company_id, contract_specification_id)
    WHERE is_delete = false AND contract_specification_id IS NOT NULL;
