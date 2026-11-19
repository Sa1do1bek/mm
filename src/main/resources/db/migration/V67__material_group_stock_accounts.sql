ALTER TABLE material_groups
    ADD COLUMN IF NOT EXISTS default_stock_account_id UUID;

ALTER TABLE inventory_accounting_settings
    ADD COLUMN IF NOT EXISTS default_stock_account_id UUID;

ALTER TABLE stock_movements
    ADD COLUMN IF NOT EXISTS account_code_snapshot VARCHAR(40);

ALTER TABLE material_groups
    ADD CONSTRAINT fk_material_group_default_stock_account
    FOREIGN KEY (default_stock_account_id) REFERENCES chart_of_accounts(id);

ALTER TABLE inventory_accounting_settings
    ADD CONSTRAINT fk_inventory_settings_default_stock_account
    FOREIGN KEY (default_stock_account_id) REFERENCES chart_of_accounts(id);

CREATE INDEX IF NOT EXISTS idx_material_group_default_stock_account
    ON material_groups(tenant_id, company_id, default_stock_account_id)
    WHERE is_delete = false;

CREATE INDEX IF NOT EXISTS idx_stock_movement_account_snapshot
    ON stock_movements(tenant_id, company_id, account_code_snapshot, movement_date)
    WHERE posted_at IS NOT NULL;
