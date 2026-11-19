ALTER TABLE erp_exchange_envelope ADD COLUMN confirm_requested boolean NOT NULL DEFAULT false;
UPDATE erp_exchange_envelope SET delivery_state='LEGACY_LOCAL' WHERE operation IS NULL AND delivery_state='POSTED';
