ALTER TABLE public.asset_handovers ADD COLUMN contract_id uuid;

ALTER TABLE public.asset_handovers
    ADD CONSTRAINT fk_asset_handovers_contract FOREIGN KEY (contract_id) REFERENCES public.contracts(id) NOT VALID;

CREATE INDEX idx_asset_handovers_contract ON public.asset_handovers (tenant_id, company_id, contract_id) WHERE is_delete = false;
