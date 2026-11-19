ALTER TABLE erp_exchange_envelope DROP CONSTRAINT uq_erp_exchange_envelope;
ALTER TABLE erp_exchange_envelope ADD COLUMN operation varchar(32), ADD COLUMN posting_date date,
 ADD COLUMN attempt_count integer NOT NULL DEFAULT 0, ADD COLUMN next_attempt_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 ADD COLUMN remote_document_ref uuid, ADD COLUMN remote_posted boolean;
CREATE UNIQUE INDEX uq_erp_exchange_event ON erp_exchange_envelope(tenant_id,company_id,document_kind,document_id,document_version,operation) WHERE operation IS NOT NULL;
ALTER TABLE erp_module_posting ALTER COLUMN quantity TYPE numeric(22,7);
ALTER TABLE erp_module_balance ALTER COLUMN quantity TYPE numeric(22,7);
