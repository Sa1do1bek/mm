CREATE TABLE warehouse_non_moving_preferences (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES companies(id),
    user_id uuid NOT NULL REFERENCES users(id),
    threshold_months integer NOT NULL CHECK (threshold_months BETWEEN 1 AND 1200),
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT uk_warehouse_non_moving_preference_scope UNIQUE (tenant_id, company_id, user_id)
);

-- The new read model filters one warehouse, then aggregates movement dates by material.
CREATE INDEX idx_non_moving_movement_source
    ON stock_movements (tenant_id, company_id, warehouse_id, material_id)
    WHERE is_delete=false AND status='POSTED';
CREATE INDEX idx_non_moving_movement_target
    ON stock_movements (tenant_id, company_id, target_warehouse_id, material_id)
    WHERE is_delete=false AND status='POSTED' AND target_warehouse_id IS NOT NULL;
CREATE INDEX idx_non_moving_allocation_source
    ON stock_movement_allocations (tenant_id, company_id, source_warehouse_id_snapshot, material_id)
    WHERE on_hand_delta<0;
CREATE INDEX idx_non_moving_allocation_target
    ON stock_movement_allocations (tenant_id, company_id, target_warehouse_id_snapshot, material_id)
    WHERE on_hand_delta>0;
CREATE INDEX idx_non_moving_posting_stock
    ON warehouse_posting_effects (warehouse_id, material_id, batch_id)
    WHERE effect_type='STOCK' AND quantity<>0;
