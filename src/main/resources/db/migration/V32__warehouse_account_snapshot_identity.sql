-- Canonical source keys repeat in later full snapshots, but may occur only once
-- across all manifests for one snapshot (including same-date addenda).
ALTER TABLE warehouse_account_entries ADD COLUMN snapshot_date date;
UPDATE warehouse_account_entries e SET snapshot_date = r.snapshot_date
FROM warehouse_account_imports r WHERE r.id = e.import_id;
ALTER TABLE warehouse_account_entries ALTER COLUMN snapshot_date SET NOT NULL;

DO $$ DECLARE item record; BEGIN
    FOR item IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'warehouse_account_entries'::regclass AND contype = 'u'
          AND pg_get_constraintdef(oid) LIKE '%tenant_id, company_id, source_key%'
    LOOP EXECUTE format('ALTER TABLE warehouse_account_entries DROP CONSTRAINT %I', item.conname); END LOOP;
END $$;
CREATE UNIQUE INDEX warehouse_account_snapshot_source_key
    ON warehouse_account_entries(tenant_id, company_id, snapshot_date, source_key);

CREATE FUNCTION warehouse_assign_account_entry_snapshot() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.snapshot_date := (SELECT r.snapshot_date FROM warehouse_account_imports r
        WHERE r.id = NEW.import_id AND r.tenant_id = NEW.tenant_id
          AND r.company_id = NEW.company_id);
    IF NEW.snapshot_date IS NULL THEN
        RAISE EXCEPTION 'Warehouse account entry import scope is invalid';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER warehouse_account_entry_snapshot_identity
BEFORE INSERT OR UPDATE ON warehouse_account_entries
FOR EACH ROW EXECUTE FUNCTION warehouse_assign_account_entry_snapshot();
