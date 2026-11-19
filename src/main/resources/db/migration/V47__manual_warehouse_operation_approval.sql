ALTER TABLE stock_movement_documents
    ADD COLUMN IF NOT EXISTS manual_operation_payload text;

COMMENT ON COLUMN stock_movement_documents.manual_operation_payload IS
    'Immutable JSON command snapshot for a MANUAL_WAREHOUSE receipt or issue awaiting approval';
