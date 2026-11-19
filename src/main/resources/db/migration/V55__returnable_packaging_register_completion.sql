ALTER TABLE returnable_packaging_lots
    ADD COLUMN source_type varchar(30) NOT NULL DEFAULT 'PURCHASE_ORDER',
    ADD COLUMN manual_document_id uuid REFERENCES stock_movement_documents(id),
    ADD COLUMN manual_content_movement_id uuid REFERENCES stock_movements(id),
    ADD COLUMN due_date date;

ALTER TABLE returnable_packaging_lots
    ALTER COLUMN purchase_order_item_id DROP NOT NULL,
    ALTER COLUMN goods_receipt_id DROP NOT NULL,
    ALTER COLUMN goods_receipt_line_id DROP NOT NULL;

ALTER TABLE returnable_packaging_lots ADD CONSTRAINT packaging_lot_source_check CHECK (
    (source_type = 'PURCHASE_ORDER' AND purchase_order_item_id IS NOT NULL
        AND goods_receipt_id IS NOT NULL AND goods_receipt_line_id IS NOT NULL
        AND manual_document_id IS NULL AND manual_content_movement_id IS NULL)
    OR (source_type = 'MANUAL_WAREHOUSE' AND manual_document_id IS NOT NULL
        AND manual_content_movement_id IS NOT NULL AND purchase_order_item_id IS NULL
        AND goods_receipt_id IS NULL AND goods_receipt_line_id IS NULL)
);
CREATE UNIQUE INDEX packaging_lot_manual_receipt_unique ON returnable_packaging_lots
    (tenant_id, company_id, manual_content_movement_id, material_id)
    WHERE is_delete = false AND source_type = 'MANUAL_WAREHOUSE';
CREATE INDEX packaging_lot_received_date ON returnable_packaging_lots
    (tenant_id, company_id, created_at DESC, id DESC) WHERE is_delete = false;
CREATE INDEX packaging_lot_due_date ON returnable_packaging_lots
    (tenant_id, company_id, due_date) WHERE is_delete = false AND due_date IS NOT NULL;

ALTER TABLE returnable_packaging_lots DROP CONSTRAINT packaging_lot_posting_check;
ALTER TABLE returnable_packaging_lots ADD CONSTRAINT packaging_lot_posting_check CHECK (
    (ownership = 'COMPANY' AND receipt_stock_movement_id IS NOT NULL)
    OR (ownership = 'SUPPLIER' AND receipt_posting_batch_id IS NOT NULL
        AND receipt_stock_movement_id IS NULL)
);

ALTER TABLE returnable_packaging_returns
    ADD COLUMN recalled_quantity numeric(22,7) NOT NULL DEFAULT 0;
ALTER TABLE returnable_packaging_returns DROP CONSTRAINT packaging_return_confirmed_check;
ALTER TABLE returnable_packaging_returns ADD CONSTRAINT packaging_return_resolution_check CHECK (
    recalled_quantity >= 0 AND confirmed_quantity + recalled_quantity <= quantity
);

ALTER TABLE returnable_packaging_events
    ADD COLUMN reason text,
    ADD COLUMN content_movement_id uuid REFERENCES stock_movements(id);
