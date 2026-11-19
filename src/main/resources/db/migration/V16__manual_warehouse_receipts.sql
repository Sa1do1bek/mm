-- Manual documents preserve warehouse evidence without requiring procurement records.
ALTER TABLE asset_acceptances ALTER COLUMN purchase_order_id DROP NOT NULL;
ALTER TABLE asset_acceptance_lines ALTER COLUMN purchase_order_item_id DROP NOT NULL;
ALTER TABLE asset_acceptance_units ALTER COLUMN purchase_order_item_id DROP NOT NULL;

ALTER TABLE material_units ALTER COLUMN goods_receipt_line_id DROP NOT NULL;
ALTER TABLE material_units ALTER COLUMN goods_receipt_unit_id DROP NOT NULL;
ALTER TABLE material_units ADD COLUMN manual_stock_movement_id uuid REFERENCES stock_movements(id);
ALTER TABLE material_units ADD CONSTRAINT ck_material_unit_receipt_source CHECK (
    (goods_receipt_line_id IS NOT NULL AND goods_receipt_unit_id IS NOT NULL)
    OR manual_stock_movement_id IS NOT NULL
);
