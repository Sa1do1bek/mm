ALTER TABLE inventory_documents ADD COLUMN scope_mode varchar(24);

CREATE TABLE inventory_document_departments (
    inventory_document_id uuid NOT NULL REFERENCES inventory_documents(id) ON DELETE CASCADE,
    department_id uuid NOT NULL,
    PRIMARY KEY (inventory_document_id, department_id)
);

CREATE TABLE inventory_document_selected_warehouses (
    inventory_document_id uuid NOT NULL REFERENCES inventory_documents(id) ON DELETE CASCADE,
    warehouse_id uuid NOT NULL,
    PRIMARY KEY (inventory_document_id, warehouse_id)
);

CREATE TABLE inventory_document_resolved_warehouses (
    inventory_document_id uuid NOT NULL REFERENCES inventory_documents(id) ON DELETE CASCADE,
    warehouse_id uuid NOT NULL,
    PRIMARY KEY (inventory_document_id, warehouse_id)
);

CREATE INDEX idx_inventory_document_resolved_warehouse
    ON inventory_document_resolved_warehouses (warehouse_id, inventory_document_id);
