ALTER TABLE asset_acceptances
    ADD COLUMN IF NOT EXISTS contract_id UUID;

UPDATE asset_acceptances aa
SET contract_id = po.contract_id
FROM purchase_orders po
WHERE aa.purchase_order_id = po.id
  AND aa.contract_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_asset_acceptances_contract_id
    ON asset_acceptances (contract_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_asset_acceptances_contract'
    ) THEN
        ALTER TABLE asset_acceptances
            ADD CONSTRAINT fk_asset_acceptances_contract
            FOREIGN KEY (contract_id) REFERENCES contracts(id);
    END IF;
END $$;

ALTER TABLE goods_receipts
    ADD COLUMN IF NOT EXISTS contract_id UUID;

-- Existing receipts are immutable. Suspend only their append-only guard for
-- this backfill; Flyway's transaction keeps the table locked until it is restored.
ALTER TABLE goods_receipts DISABLE TRIGGER trg_goods_receipts_append_only;

UPDATE goods_receipts gr
SET contract_id = po.contract_id
FROM purchase_orders po
WHERE gr.purchase_order_id = po.id
  AND gr.contract_id IS NULL;

ALTER TABLE goods_receipts ENABLE TRIGGER trg_goods_receipts_append_only;

CREATE INDEX IF NOT EXISTS idx_goods_receipts_contract_id
    ON goods_receipts (contract_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_goods_receipts_contract'
    ) THEN
        ALTER TABLE goods_receipts
            ADD CONSTRAINT fk_goods_receipts_contract
            FOREIGN KEY (contract_id) REFERENCES contracts(id);
    END IF;
END $$;
