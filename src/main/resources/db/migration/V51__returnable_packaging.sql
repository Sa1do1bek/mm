ALTER TABLE materials DROP CONSTRAINT materials_subtype_business_type_check;
ALTER TABLE materials ADD CONSTRAINT materials_subtype_business_type_check CHECK (
    material_subtype IS NULL OR (material_type = 'TMB' AND material_subtype IN
    ('MATERIAL','EQUIPMENT','ANIMALS','FINISHED_GOODS','SEMI_FINISHED_GOODS','GOODS','RETURNABLE_PACKAGING'))
);

CREATE TABLE returnable_packaging_lots (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES companies(id),
    code varchar(80), name text NOT NULL, status varchar(50), description text,
    created_at timestamp NOT NULL, updated_at timestamp, created_by varchar(120), updated_by varchar(120),
    version bigint NOT NULL DEFAULT 0, is_delete boolean NOT NULL DEFAULT false,
    supplier_id uuid NOT NULL REFERENCES suppliers(id),
    ownership varchar(30) NOT NULL CHECK (ownership IN ('SUPPLIER','COMPANY')),
    account_code varchar(30) NOT NULL,
    unit_cost numeric(19,4) NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
    material_id uuid NOT NULL REFERENCES materials(id),
    content_material_id uuid NOT NULL REFERENCES materials(id),
    purchase_order_item_id uuid NOT NULL REFERENCES purchase_order_items(id),
    goods_receipt_id uuid NOT NULL REFERENCES goods_receipts(id),
    goods_receipt_line_id uuid NOT NULL REFERENCES goods_receipt_lines(id),
    receipt_stock_movement_id uuid REFERENCES stock_movements(id),
    receipt_posting_batch_id uuid REFERENCES warehouse_posting_batches(id),
    warehouse_id uuid NOT NULL REFERENCES warehouses(id),
    storage_location_id uuid,
    storage_bin_id uuid,
    stock_batch_id uuid,
    received_quantity numeric(22,7) NOT NULL CHECK (received_quantity > 0),
    filled_quantity numeric(22,7) NOT NULL CHECK (filled_quantity >= 0),
    partial_quantity numeric(22,7) NOT NULL DEFAULT 0 CHECK (partial_quantity >= 0),
    empty_quantity numeric(22,7) NOT NULL DEFAULT 0 CHECK (empty_quantity >= 0),
    damaged_quantity numeric(22,7) NOT NULL DEFAULT 0 CHECK (damaged_quantity >= 0),
    reserved_quantity numeric(22,7) NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    dispatched_quantity numeric(22,7) NOT NULL DEFAULT 0 CHECK (dispatched_quantity >= 0),
    confirmed_quantity numeric(22,7) NOT NULL DEFAULT 0 CHECK (confirmed_quantity >= 0),
    CONSTRAINT packaging_lot_quantity_check CHECK (
        received_quantity = filled_quantity + partial_quantity + empty_quantity + damaged_quantity + dispatched_quantity
        AND reserved_quantity <= empty_quantity AND confirmed_quantity <= dispatched_quantity
    ),
    CONSTRAINT packaging_lot_posting_check CHECK (
        (ownership = 'COMPANY' AND receipt_stock_movement_id IS NOT NULL AND receipt_posting_batch_id IS NULL)
        OR (ownership = 'SUPPLIER' AND receipt_posting_batch_id IS NOT NULL AND receipt_stock_movement_id IS NULL)
    ),
    CONSTRAINT packaging_lot_receipt_unique UNIQUE (tenant_id, company_id, goods_receipt_line_id, material_id)
);
CREATE INDEX packaging_lot_register ON returnable_packaging_lots
    (tenant_id, company_id, supplier_id, material_id, warehouse_id) WHERE is_delete = false;

CREATE TABLE returnable_packaging_returns (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES companies(id),
    code varchar(80), name text NOT NULL, status varchar(50) NOT NULL, description text,
    created_at timestamp NOT NULL, updated_at timestamp, created_by varchar(120), updated_by varchar(120),
    version bigint NOT NULL DEFAULT 0, is_delete boolean NOT NULL DEFAULT false,
    lot_id uuid NOT NULL REFERENCES returnable_packaging_lots(id),
    supplier_id uuid NOT NULL REFERENCES suppliers(id),
    quantity numeric(22,7) NOT NULL CHECK (quantity > 0),
    confirmed_quantity numeric(22,7) NOT NULL DEFAULT 0 CHECK (confirmed_quantity >= 0),
    dispatch_stock_movement_id uuid REFERENCES stock_movements(id),
    dispatch_posting_batch_id uuid REFERENCES warehouse_posting_batches(id),
    recall_stock_movement_id uuid REFERENCES stock_movements(id),
    recall_posting_batch_id uuid REFERENCES warehouse_posting_batches(id),
    document_number varchar(120), reason text,
    recall_reason text,
    approved_at timestamp, dispatched_at timestamp, confirmed_at timestamp, recalled_at timestamp,
    CONSTRAINT packaging_return_confirmed_check CHECK (confirmed_quantity <= quantity)
);
CREATE INDEX packaging_return_register ON returnable_packaging_returns
    (tenant_id, company_id, supplier_id, status, created_at) WHERE is_delete = false;

CREATE TABLE returnable_packaging_events (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES companies(id),
    code varchar(80), name text NOT NULL, status varchar(50), description text,
    created_at timestamp NOT NULL, updated_at timestamp, created_by varchar(120), updated_by varchar(120),
    version bigint NOT NULL DEFAULT 0, is_delete boolean NOT NULL DEFAULT false,
    lot_id uuid NOT NULL REFERENCES returnable_packaging_lots(id),
    return_id uuid REFERENCES returnable_packaging_returns(id),
    event_type varchar(40) NOT NULL,
    quantity numeric(22,7) NOT NULL CHECK (quantity > 0),
    from_state varchar(30), to_state varchar(30),
    stock_movement_id uuid REFERENCES stock_movements(id),
    posting_batch_id uuid REFERENCES warehouse_posting_batches(id)
);
CREATE INDEX packaging_event_history ON returnable_packaging_events
    (tenant_id, company_id, lot_id, created_at, id);
