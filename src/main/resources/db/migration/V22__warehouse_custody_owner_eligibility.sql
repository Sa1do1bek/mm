-- V20: Custody Owner Eligibility and Position Isolation.
-- Enhances multi-owner and off-balance custody (Schet 002) position indexing on subledger posting evidence.

CREATE INDEX IF NOT EXISTS warehouse_posting_effect_owner_cat
    ON warehouse_posting_effects (material_id, warehouse_id, owner_id, stock_category, account_code);

CREATE INDEX IF NOT EXISTS warehouse_posting_batches_scope_status
    ON warehouse_posting_batches (tenant_id, company_id, status);
