CREATE TABLE warehouse_production_operations (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    company_id UUID NOT NULL,
    warehouse_id UUID NOT NULL REFERENCES warehouses(id),
    operation_number VARCHAR(120) NOT NULL,
    operation_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    operation_date DATE NOT NULL,
    payload TEXT NOT NULL,
    additional_cost NUMERIC(19,4) NOT NULL DEFAULT 0,
    input_cost NUMERIC(19,4),
    output_cost NUMERIC(19,4),
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_by VARCHAR(120),
    posted_at TIMESTAMP WITH TIME ZONE,
    reversal_reason VARCHAR(1000),
    reversed_by VARCHAR(120),
    reversed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_production_operation_number UNIQUE(tenant_id, company_id, operation_number),
    CONSTRAINT ck_production_operation_status CHECK(status IN ('DRAFT','POSTED','REVERSED','CANCELLED')),
    CONSTRAINT ck_production_additional_cost CHECK(additional_cost >= 0)
);
CREATE TABLE warehouse_production_operation_lines (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES warehouse_production_operations(id),
    sequence_number INT NOT NULL,
    line_type VARCHAR(20) NOT NULL,
    material_id UUID NOT NULL REFERENCES materials(id),
    material_code VARCHAR(120) NOT NULL,
    material_name TEXT NOT NULL,
    unit_code VARCHAR(80) NOT NULL,
    quantity NUMERIC(22,7) NOT NULL CHECK(quantity > 0),
    allocated_cost NUMERIC(19,4) NOT NULL CHECK(allocated_cost >= 0),
    stock_position_id UUID,
    stock_batch_id UUID NOT NULL REFERENCES stock_batches(id),
    movement_id UUID NOT NULL REFERENCES stock_movements(id),
    reversal_movement_id UUID REFERENCES stock_movements(id),
    CONSTRAINT uk_production_operation_line UNIQUE(operation_id, sequence_number)
);
CREATE INDEX idx_production_operations_scope ON warehouse_production_operations(tenant_id,company_id,warehouse_id,created_at);
CREATE INDEX idx_production_lines_material_batch ON warehouse_production_operation_lines(material_id,stock_batch_id);
CREATE INDEX idx_production_lines_movement ON warehouse_production_operation_lines(movement_id);
