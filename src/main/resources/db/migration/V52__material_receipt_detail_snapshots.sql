-- Preserve source evidence at posting time. Legacy prices deliberately remain unknown:
-- current purchase order prices are not evidence of an earlier receipt price.
ALTER TABLE stock_movements ADD COLUMN source_unit_price_snapshot numeric(19,6);
ALTER TABLE goods_receipt_batches ADD COLUMN stock_movement_id uuid;

CREATE INDEX idx_goods_receipt_batches_movement_detail
    ON goods_receipt_batches (tenant_id, company_id, stock_movement_id)
    WHERE stock_movement_id IS NOT NULL AND is_delete = false;
