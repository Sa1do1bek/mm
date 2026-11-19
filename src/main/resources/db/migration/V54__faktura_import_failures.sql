CREATE TABLE public.faktura_import_failures (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES public.faktura_import_runs(id) ON DELETE CASCADE,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL REFERENCES public.companies(id),
    document_type integer NOT NULL,
    source_unique_id varchar(180),
    document_number varchar(180),
    stage varchar(40) NOT NULL,
    reason_code varchar(60) NOT NULL,
    created_at timestamp NOT NULL DEFAULT now()
);

CREATE INDEX ix_faktura_import_failures_run ON public.faktura_import_failures
    (tenant_id, company_id, run_id, created_at DESC);

CREATE INDEX ix_faktura_source_documents_browse ON public.faktura_source_documents
    (tenant_id, company_id, last_seen_at DESC, id DESC);
