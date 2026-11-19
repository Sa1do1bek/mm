CREATE TABLE public.contract_material_links (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES public.companies(id),
    specification_id uuid NOT NULL REFERENCES public.contract_specifications(id),
    material_id uuid NOT NULL REFERENCES public.materials(id),
    contract_id uuid NOT NULL REFERENCES public.contracts(id),
    contract_material_group_id uuid NOT NULL REFERENCES public.material_groups(id),
    stock_material_group_id uuid REFERENCES public.material_groups(id),
    contract_unit_of_measure_id uuid NOT NULL REFERENCES public.unit_of_measures(id),
    stock_unit_of_measure_id uuid NOT NULL REFERENCES public.unit_of_measures(id),
    contract_quantity_per_stock_quantity numeric(10,3) NOT NULL
        CHECK (contract_quantity_per_stock_quantity IN (1, 0.001, 1000)),
    source_manifest_sha256 varchar(64) NOT NULL,
    prepared_manifest_sha256 varchar(64) NOT NULL,
    source_entity_type varchar(40) NOT NULL,
    source_id varchar(32) NOT NULL,
    material_source_id varchar(32) NOT NULL,
    contract_unit_snapshot jsonb NOT NULL,
    stock_unit_snapshot jsonb NOT NULL,
    evidence jsonb NOT NULL,
    source_payload jsonb NOT NULL,
    created_at timestamp NOT NULL,
    CONSTRAINT uq_contract_material_link UNIQUE (tenant_id, company_id, specification_id, material_id)
);

COMMENT ON TABLE public.contract_material_links IS
    'Immutable verified source links. Factors express contract quantity per material stock quantity; never update after receipt.';

CREATE FUNCTION public.reject_contract_material_link_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Contract material links are immutable';
END;
$$;

CREATE TRIGGER contract_material_links_immutable
    BEFORE UPDATE OR DELETE ON public.contract_material_links
    FOR EACH ROW EXECUTE FUNCTION public.reject_contract_material_link_mutation();
