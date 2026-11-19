ALTER TABLE inventory_accounting_settings
    ADD COLUMN IF NOT EXISTS returnable_packaging_stock_account_id UUID;

ALTER TABLE inventory_accounting_settings
    ADD CONSTRAINT fk_inventory_settings_returnable_packaging_stock_account
    FOREIGN KEY (returnable_packaging_stock_account_id) REFERENCES chart_of_accounts(id);

CREATE INDEX IF NOT EXISTS idx_inventory_settings_returnable_packaging_stock_account
    ON inventory_accounting_settings(tenant_id, company_id, returnable_packaging_stock_account_id)
    WHERE is_delete = false;
