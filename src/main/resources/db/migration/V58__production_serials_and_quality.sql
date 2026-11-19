-- Keep produced physical units distinct from purchased and manually received units.
ALTER TABLE material_units ADD COLUMN production_stock_movement_id uuid REFERENCES stock_movements(id);
ALTER TABLE material_units DROP CONSTRAINT ck_material_unit_receipt_source;
ALTER TABLE material_units ADD CONSTRAINT ck_material_unit_receipt_source CHECK (
    (goods_receipt_line_id IS NOT NULL AND goods_receipt_unit_id IS NOT NULL)
    OR manual_stock_movement_id IS NOT NULL OR production_stock_movement_id IS NOT NULL
);
CREATE INDEX ix_material_units_production ON material_units(production_stock_movement_id)
    WHERE production_stock_movement_id IS NOT NULL;

ALTER TABLE material_units DROP CONSTRAINT chk_material_units_unit_status;
ALTER TABLE material_units ADD CONSTRAINT chk_material_units_unit_status CHECK (
    unit_status IN ('IN_STOCK','RESERVED','IN_TRANSIT','ISSUED','QUARANTINE','RETURNED','DISPOSED','CONSUMED'));
ALTER TABLE material_unit_operations ADD CONSTRAINT chk_material_unit_operations_from_status CHECK (
    from_status IN ('IN_STOCK','RESERVED','IN_TRANSIT','ISSUED','QUARANTINE','RETURNED','DISPOSED','CONSUMED'));
ALTER TABLE material_unit_operations DROP CONSTRAINT chk_material_unit_operations_to_status;
ALTER TABLE material_unit_operations ADD CONSTRAINT chk_material_unit_operations_to_status CHECK (
    to_status IN ('IN_STOCK','RESERVED','IN_TRANSIT','ISSUED','QUARANTINE','RETURNED','DISPOSED','CONSUMED'));
ALTER TABLE material_unit_operations DROP CONSTRAINT chk_material_unit_operations_type;
ALTER TABLE material_unit_operations ADD CONSTRAINT chk_material_unit_operations_type CHECK (
    operation_type IN ('RESERVATION','ISSUE','TRANSFER','RETURN','INVENTORY_COUNT','ADJUSTMENT','PRODUCTION','QUALITY'));
ALTER TABLE material_unit_events DROP CONSTRAINT chk_material_unit_events_event_type;
ALTER TABLE material_unit_events ADD CONSTRAINT chk_material_unit_events_event_type CHECK (
    event_type IN ('RECEIVED','RESERVED','RESERVATION_RELEASED','ISSUED','TRANSFER_DISPATCHED','TRANSFER_RECEIVED',
        'RETURNED','ADJUSTED_IN','ADJUSTED_OUT','PRODUCTION_CREATED','PRODUCTION_CONSUMED','PRODUCTION_REVERSED',
        'QUALITY_HELD','QUALITY_RELEASED'));
CREATE UNIQUE INDEX uq_quality_inspections_production_movement ON quality_inspections(stock_movement_id)
    WHERE inspection_type='PRODUCTION' AND is_delete=false;
