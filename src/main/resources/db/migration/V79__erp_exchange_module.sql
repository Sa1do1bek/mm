CREATE TABLE erp_exchange_connection (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    enabled boolean NOT NULL DEFAULT false,
    base_url varchar(500),
    username varchar(160),
    password_value varchar(240),
    schedule_cron varchar(80),
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_erp_exchange_connection UNIQUE (tenant_id, company_id)
);

CREATE TABLE erp_exchange_posting_config (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    arrival_mode varchar(40) NOT NULL DEFAULT 'HOLD_UNTIL_CONFIRMED',
    account_code varchar(40),
    subconto_code varchar(80),
    warehouse_code varchar(80),
    updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_erp_exchange_posting_config UNIQUE (tenant_id, company_id)
);

CREATE TABLE erp_exchange_envelope (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    document_kind varchar(20) NOT NULL,
    document_id uuid NOT NULL,
    document_version bigint NOT NULL,
    payload text NOT NULL,
    delivery_state varchar(40) NOT NULL,
    rejection_reason text,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_erp_exchange_envelope UNIQUE (tenant_id, company_id, document_kind, document_id, document_version)
);

CREATE INDEX idx_erp_exchange_envelope_company ON erp_exchange_envelope (tenant_id, company_id, created_at DESC);

CREATE TABLE erp_module_document (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    envelope_id uuid NOT NULL UNIQUE REFERENCES erp_exchange_envelope(id),
    document_kind varchar(20) NOT NULL,
    document_id uuid NOT NULL,
    document_version bigint NOT NULL,
    posted boolean NOT NULL DEFAULT false,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_erp_module_document UNIQUE (tenant_id, company_id, document_kind, document_id, document_version)
);

CREATE TABLE erp_module_posting (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    document_row_id uuid NOT NULL REFERENCES erp_module_document(id),
    envelope_id uuid NOT NULL REFERENCES erp_exchange_envelope(id),
    account_code varchar(40),
    subconto_code varchar(80),
    warehouse_code varchar(80),
    material_id uuid,
    warehouse_id uuid,
    quantity numeric(19, 4) NOT NULL DEFAULT 0,
    amount numeric(19, 4) NOT NULL DEFAULT 0,
    posting_date date NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_erp_module_posting_envelope ON erp_module_posting (envelope_id);

CREATE TABLE erp_module_confirmation (
    id uuid PRIMARY KEY,
    document_row_id uuid NOT NULL UNIQUE REFERENCES erp_module_document(id),
    confirmed_by varchar(160) NOT NULL,
    confirmed_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note varchar(240)
);

CREATE TABLE erp_module_balance (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    balance_date date NOT NULL,
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    quantity numeric(19, 4) NOT NULL DEFAULT 0,
    amount numeric(19, 4) NOT NULL DEFAULT 0,
    absolute boolean NOT NULL DEFAULT false,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_erp_module_balance UNIQUE (tenant_id, company_id, balance_date, material_id, warehouse_id)
);

CREATE TABLE erp_exchange_reconciliation_run (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    balance_date date NOT NULL,
    difference_count integer NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE unit_marks (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    material_id uuid NOT NULL,
    goods_receipt_id uuid,
    mark_code varchar(160) NOT NULL,
    is_delete boolean NOT NULL DEFAULT false,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_unit_marks_code UNIQUE (tenant_id, company_id, mark_code)
);

ALTER TABLE materials ADD COLUMN analytics_1 varchar(160);
ALTER TABLE materials ADD COLUMN analytics_2 varchar(160);
ALTER TABLE materials ADD COLUMN analytics_3 varchar(160);
ALTER TABLE material_groups ADD COLUMN additional_category varchar(160);
ALTER TABLE material_groups ADD COLUMN lower_category varchar(160);
