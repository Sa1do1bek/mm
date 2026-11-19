ALTER TABLE materials ADD COLUMN manual_receipt_stock_account_id UUID;

ALTER TABLE materials ADD CONSTRAINT fk_material_manual_receipt_stock_account
    FOREIGN KEY (manual_receipt_stock_account_id) REFERENCES chart_of_accounts(id);
