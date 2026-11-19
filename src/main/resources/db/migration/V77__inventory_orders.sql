CREATE TABLE inventory_orders (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    owner_username varchar(120) NOT NULL,
    source_draft_id uuid NOT NULL UNIQUE REFERENCES inventory_ai_drafts(id),
    analysis_job_id uuid NOT NULL UNIQUE REFERENCES ai_analysis_jobs(id),
    analysis_payload text NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_inventory_orders_company ON inventory_orders(tenant_id, company_id, created_at DESC);

ALTER TABLE inventory_ai_drafts ADD COLUMN inventory_order_id uuid REFERENCES inventory_orders(id);

CREATE TABLE inventory_order_allocations (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES inventory_orders(id),
    source_keys text NOT NULL,
    task_type varchar(40) NOT NULL,
    request_payload text NOT NULL,
    inventory_document_id uuid UNIQUE REFERENCES inventory_documents(id),
    commission_id uuid REFERENCES commissions(id),
    status varchar(30) NOT NULL DEFAULT 'OPEN',
    result_note text,
    approver_user_id uuid REFERENCES users(id),
    submitted_at timestamp,
    approved_at timestamp,
    accepted_at timestamp,
    approved_by varchar(120),
    accepted_by varchar(120),
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_inventory_order_allocations_order ON inventory_order_allocations(order_id, created_at);
