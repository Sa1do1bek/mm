-- PR 1D: Activation Journal and Reconciliation Evidence
-- Tracks approved opening activation runs, hashes, deltas, and receipts.
CREATE TABLE warehouse_activation_journals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    idempotency_key varchar(160) NOT NULL,
    account_code varchar(40),
    warehouse_id uuid,
    posting_date date NOT NULL,
    preview_hash varchar(64) NOT NULL,
    source_content_hash varchar(64) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'APPLIED' CHECK (status IN ('APPLIED', 'REJECTED')),
    applied_count integer NOT NULL DEFAULT 0 CHECK (applied_count >= 0),
    total_quantity numeric(18, 7) NOT NULL DEFAULT 0,
    total_amount numeric(18, 4) NOT NULL DEFAULT 0,
    batch_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    receipt_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT warehouse_activation_journal_idempotency UNIQUE (tenant_id, company_id, idempotency_key)
);

CREATE INDEX idx_warehouse_activation_scope ON warehouse_activation_journals (tenant_id, company_id, posting_date);
CREATE INDEX idx_warehouse_activation_acct_wh ON warehouse_activation_journals (tenant_id, company_id, account_code, warehouse_id);
