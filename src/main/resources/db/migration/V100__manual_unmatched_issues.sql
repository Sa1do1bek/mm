-- A completed physical handover may be recorded before its source warehouse or
-- opening stock has been imported. It is an operational fact, not a stock debit.
CREATE TABLE manual_unmatched_issues (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    code varchar(80) NOT NULL,
    name text NOT NULL,
    status varchar(50) NOT NULL CHECK (status IN ('POSTED_UNMATCHED', 'RECONCILED')),
    description text,
    issue_date date NOT NULL,
    issued_to varchar(240) NOT NULL,
    reference varchar(120),
    entry_context_warehouse_id uuid NOT NULL REFERENCES warehouses(id),
    source_warehouse_id uuid REFERENCES warehouses(id),
    created_at timestamp NOT NULL,
    created_by varchar(120),
    updated_at timestamp,
    updated_by varchar(120),
    version bigint NOT NULL DEFAULT 0,
    is_delete boolean NOT NULL DEFAULT false,
    CONSTRAINT manual_unmatched_issues_code_scope UNIQUE (tenant_id, company_id, code)
);

CREATE TABLE manual_unmatched_issue_lines (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    code varchar(80),
    name text NOT NULL,
    status varchar(50),
    description text,
    issue_id uuid NOT NULL REFERENCES manual_unmatched_issues(id),
    line_number integer NOT NULL,
    material_id uuid NOT NULL REFERENCES materials(id),
    material_code_snapshot varchar(80) NOT NULL,
    material_name_snapshot text NOT NULL,
    unit_code_snapshot varchar(80) NOT NULL,
    quantity numeric(22,7) NOT NULL CHECK (quantity > 0),
    created_at timestamp NOT NULL,
    created_by varchar(120),
    updated_at timestamp,
    updated_by varchar(120),
    version bigint NOT NULL DEFAULT 0,
    is_delete boolean NOT NULL DEFAULT false,
    CONSTRAINT manual_unmatched_issue_material_once UNIQUE (issue_id, material_id),
    CONSTRAINT manual_unmatched_issue_order UNIQUE (issue_id, line_number)
);

CREATE INDEX manual_unmatched_issues_scope_date
    ON manual_unmatched_issues (tenant_id, company_id, entry_context_warehouse_id, issue_date DESC, created_at DESC);
CREATE INDEX manual_unmatched_issue_lines_issue ON manual_unmatched_issue_lines (issue_id);
