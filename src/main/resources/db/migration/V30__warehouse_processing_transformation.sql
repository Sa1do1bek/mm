CREATE TABLE IF NOT EXISTS warehouse_transformation_orders (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL,
    company_id VARCHAR(50) NOT NULL,
    order_number VARCHAR(120) NOT NULL,
    transformation_type VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    warehouse_id UUID NOT NULL,
    processor_id UUID,
    total_input_cost NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_output_cost NUMERIC(19, 4) NOT NULL DEFAULT 0,
    execution_date DATE NOT NULL,
    source_reference VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_wh_transf_order UNIQUE (tenant_id, company_id, order_number)
);

CREATE TABLE IF NOT EXISTS warehouse_transformation_lines (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES warehouse_transformation_orders(id) ON DELETE CASCADE,
    line_type VARCHAR(20) NOT NULL,
    material_id UUID NOT NULL,
    account_code VARCHAR(20) NOT NULL,
    quantity NUMERIC(19, 7) NOT NULL,
    unit_cost NUMERIC(19, 4) NOT NULL DEFAULT 0,
    allocated_cost NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wh_transf_ord_status 
    ON warehouse_transformation_orders (tenant_id, company_id, status);

CREATE INDEX IF NOT EXISTS idx_wh_transf_line_order 
    ON warehouse_transformation_lines (order_id);
