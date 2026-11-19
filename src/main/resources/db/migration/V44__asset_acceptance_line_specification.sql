ALTER TABLE public.asset_acceptance_lines
    ADD COLUMN IF NOT EXISTS contract_specification_id UUID;

CREATE INDEX IF NOT EXISTS idx_asset_acceptance_lines_contract_spec
    ON public.asset_acceptance_lines (contract_specification_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_asset_acceptance_lines_contract_spec'
    ) THEN
        ALTER TABLE public.asset_acceptance_lines
            ADD CONSTRAINT fk_asset_acceptance_lines_contract_spec
            FOREIGN KEY (contract_specification_id) REFERENCES public.contract_specifications(id);
    END IF;
END $$;
