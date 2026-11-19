CREATE TABLE public.faktura_source_documents (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES public.companies(id),
    source_unique_id varchar(180) NOT NULL,
    roaming_uid varchar(180),
    document_type integer NOT NULL CHECK (document_type IN (20, 28, 29)),
    source_status integer,
    source_created_at timestamp,
    source_updated_at timestamp,
    first_seen_at timestamp NOT NULL DEFAULT now(),
    last_seen_at timestamp NOT NULL DEFAULT now(),
    document_number varchar(180),
    document_date date,
    valid_to date,
    contract_number varchar(180),
    contract_date date,
    owner_inn varchar(20),
    owner_name varchar(300),
    contractor_inn varchar(20),
    contractor_name varchar(300),
    recipient_name varchar(300),
    issuer_name varchar(300),
    expediter_name varchar(300),
    amount numeric(19,4),
    contract_id uuid REFERENCES public.contracts(id),
    match_state varchar(32) NOT NULL DEFAULT 'UNMATCHED',
    match_reason varchar(240),
    matched_by varchar(120),
    matched_at timestamp,
    payload_sha256 varchar(64) NOT NULL,
    source_payload jsonb NOT NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now(),
    CONSTRAINT uq_faktura_source_document UNIQUE (tenant_id, company_id, source_unique_id)
);

CREATE INDEX ix_faktura_source_contract ON public.faktura_source_documents
    (tenant_id, company_id, contract_id, document_type);
CREATE INDEX ix_faktura_source_review ON public.faktura_source_documents
    (tenant_id, company_id, match_state, last_seen_at DESC);

CREATE TABLE public.faktura_source_lines (
    id uuid PRIMARY KEY,
    source_document_id uuid NOT NULL REFERENCES public.faktura_source_documents(id) ON DELETE CASCADE,
    line_kind varchar(24) NOT NULL,
    line_index integer NOT NULL,
    source_line_number varchar(80),
    title text,
    measurement varchar(120),
    quantity numeric(19,4),
    unit_price numeric(19,4),
    amount numeric(19,4),
    catalog_code varchar(120),
    source_line jsonb NOT NULL,
    CONSTRAINT uq_faktura_source_line UNIQUE (source_document_id, line_kind, line_index)
);

CREATE TABLE public.faktura_import_runs (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES public.companies(id),
    requested_from date NOT NULL,
    requested_to date NOT NULL,
    started_at timestamp NOT NULL DEFAULT now(),
    finished_at timestamp,
    status varchar(24) NOT NULL,
    fetched integer NOT NULL DEFAULT 0,
    created integer NOT NULL DEFAULT 0,
    updated integer NOT NULL DEFAULT 0,
    unchanged integer NOT NULL DEFAULT 0,
    failed integer NOT NULL DEFAULT 0,
    error_message varchar(500)
);

CREATE INDEX ix_faktura_runs_company ON public.faktura_import_runs
    (tenant_id, company_id, started_at DESC);
CREATE UNIQUE INDEX uq_faktura_one_running_import ON public.faktura_import_runs
    (tenant_id, company_id) WHERE status = 'RUNNING';

CREATE TABLE public.faktura_receipt_evidence (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES public.companies(id),
    contract_id uuid NOT NULL REFERENCES public.contracts(id),
    document_role varchar(24) NOT NULL CHECK (document_role IN ('POWER_OF_ATTORNEY','INVOICE')),
    faktura_document_id uuid REFERENCES public.faktura_source_documents(id),
    stock_movement_document_id uuid REFERENCES public.stock_movement_documents(id),
    goods_receipt_id uuid REFERENCES public.goods_receipts(id),
    asset_acceptance_id uuid REFERENCES public.asset_acceptances(id),
    source_payload_sha256 varchar(64),
    source_status_at_selection integer,
    source_number_at_selection varchar(180),
    source_snapshot jsonb,
    selection_reason varchar(500),
    selected_by varchar(120),
    created_at timestamp NOT NULL DEFAULT now(),
    CONSTRAINT ck_faktura_receipt_one_header CHECK (
        (stock_movement_document_id IS NOT NULL)::int +
        (goods_receipt_id IS NOT NULL)::int +
        (asset_acceptance_id IS NOT NULL)::int = 1),
    CONSTRAINT ck_faktura_receipt_source_or_reason CHECK
        (faktura_document_id IS NOT NULL OR selection_reason IS NOT NULL)
);
CREATE UNIQUE INDEX uq_faktura_stock_receipt_role ON public.faktura_receipt_evidence
    (stock_movement_document_id, document_role) WHERE stock_movement_document_id IS NOT NULL;
CREATE UNIQUE INDEX uq_faktura_goods_receipt_role ON public.faktura_receipt_evidence
    (goods_receipt_id, document_role) WHERE goods_receipt_id IS NOT NULL;
CREATE UNIQUE INDEX uq_faktura_asset_receipt_role ON public.faktura_receipt_evidence
    (asset_acceptance_id, document_role) WHERE asset_acceptance_id IS NOT NULL;
