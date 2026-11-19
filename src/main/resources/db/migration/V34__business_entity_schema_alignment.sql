-- Align return origin links with the BusinessEntity fields used by Hibernate.
-- V19 created this table before those inherited fields were accounted for.
ALTER TABLE return_origin_links ADD COLUMN IF NOT EXISTS created_by VARCHAR(120);
ALTER TABLE return_origin_links ADD COLUMN IF NOT EXISTS updated_by VARCHAR(120);
ALTER TABLE return_origin_links ADD COLUMN IF NOT EXISTS code VARCHAR(80);
ALTER TABLE return_origin_links ADD COLUMN IF NOT EXISTS name TEXT NOT NULL DEFAULT 'Return origin link';
ALTER TABLE return_origin_links ADD COLUMN IF NOT EXISTS status VARCHAR(50);
ALTER TABLE return_origin_links ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE return_origin_links ADD COLUMN IF NOT EXISTS is_delete BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE return_origin_links
SET is_delete = is_deleted
WHERE is_deleted = TRUE;

-- Earlier warehouse migrations used several custom table layouts for
-- BusinessEntity subclasses. Add the inherited fields without rewriting
-- migrations that may already have been applied.
DO $$
DECLARE
    target_table TEXT;
BEGIN
    FOREACH target_table IN ARRAY ARRAY[
        'warehouse_custody_activations',
        'warehouse_custody_agreements',
        'warehouse_custody_ownership_transfers',
        'warehouse_holding_handovers',
        'warehouse_holding_transfers',
        'warehouse_holdings',
        'warehouse_transfer_group_items',
        'warehouse_transfer_groups'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS code VARCHAR(80)', target_table);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS name TEXT', target_table);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS status VARCHAR(50)', target_table);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS description TEXT', target_table);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS created_by VARCHAR(120)', target_table);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS updated_by VARCHAR(120)', target_table);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS is_delete BOOLEAN NOT NULL DEFAULT FALSE', target_table);

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = target_table
              AND column_name = 'is_deleted'
        ) THEN
            EXECUTE format('UPDATE %I SET is_delete = TRUE WHERE is_deleted = TRUE', target_table);
        END IF;

        EXECUTE format('UPDATE %I SET name = %L WHERE name IS NULL OR btrim(name) = %L',
            target_table, initcap(replace(target_table, '_', ' ')), '');
        EXECUTE format('ALTER TABLE %I ALTER COLUMN name SET NOT NULL', target_table);
    END LOOP;
END $$;
