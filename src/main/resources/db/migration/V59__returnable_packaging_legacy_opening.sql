-- Adopt existing owned stock without manufacturing a receipt, supplier or contents.
ALTER TABLE returnable_packaging_lots
    ALTER COLUMN supplier_id DROP NOT NULL,
    ALTER COLUMN content_material_id DROP NOT NULL,
    ADD COLUMN legacy_stock_balance_id uuid REFERENCES stock_balances(id),
    ADD COLUMN opening_value numeric(19,4),
    ADD COLUMN unknown_quantity numeric(22,7) NOT NULL DEFAULT 0 CHECK (unknown_quantity >= 0);

CREATE UNIQUE INDEX packaging_lot_legacy_balance_unique ON returnable_packaging_lots
    (tenant_id, company_id, legacy_stock_balance_id) WHERE legacy_stock_balance_id IS NOT NULL;

ALTER TABLE returnable_packaging_lots DROP CONSTRAINT packaging_lot_quantity_check;
ALTER TABLE returnable_packaging_lots ADD CONSTRAINT packaging_lot_quantity_check CHECK (
    received_quantity = unknown_quantity + filled_quantity + partial_quantity + empty_quantity + damaged_quantity + dispatched_quantity
    AND reserved_quantity <= empty_quantity AND confirmed_quantity <= dispatched_quantity
);
ALTER TABLE returnable_packaging_lots DROP CONSTRAINT packaging_lot_source_check;
ALTER TABLE returnable_packaging_lots ADD CONSTRAINT packaging_lot_source_check CHECK (
    (source_type = 'PURCHASE_ORDER' AND supplier_id IS NOT NULL AND content_material_id IS NOT NULL
        AND purchase_order_item_id IS NOT NULL AND goods_receipt_id IS NOT NULL AND goods_receipt_line_id IS NOT NULL
        AND manual_document_id IS NULL AND manual_content_movement_id IS NULL AND legacy_stock_balance_id IS NULL)
    OR (source_type = 'MANUAL_WAREHOUSE' AND supplier_id IS NOT NULL AND content_material_id IS NOT NULL
        AND manual_document_id IS NOT NULL AND manual_content_movement_id IS NOT NULL
        AND purchase_order_item_id IS NULL AND goods_receipt_id IS NULL AND goods_receipt_line_id IS NULL
        AND legacy_stock_balance_id IS NULL)
    OR (source_type = 'LEGACY_OPENING' AND ownership = 'COMPANY' AND legacy_stock_balance_id IS NOT NULL
        AND stock_batch_id IS NOT NULL AND opening_value IS NOT NULL AND opening_value >= 0
        AND purchase_order_item_id IS NULL AND goods_receipt_id IS NULL AND goods_receipt_line_id IS NULL
        AND manual_document_id IS NULL AND manual_content_movement_id IS NULL)
);

CREATE TABLE returnable_packaging_legacy_migrations (
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES companies(id),
    run_code varchar(80) NOT NULL,
    material_count integer NOT NULL,
    lot_count integer NOT NULL,
    stock_before jsonb NOT NULL,
    stock_after jsonb NOT NULL,
    material_before_md5 varchar(32) NOT NULL,
    material_after_md5 varchar(32) NOT NULL,
    adopted_value numeric(24,4) NOT NULL,
    migrated_at timestamp NOT NULL,
    PRIMARY KEY (tenant_id, company_id, run_code),
    CHECK (stock_before = stock_after AND material_before_md5 = material_after_md5)
);
