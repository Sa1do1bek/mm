-- Bind executable opening plans to one completed source import and cutover.
ALTER TABLE warehouse_activation_plans
    ADD COLUMN source_import_id uuid REFERENCES warehouse_account_imports(id),
    ADD COLUMN source_snapshot_date date,
    ADD COLUMN source_manifest_sha256 varchar(64),
    ADD COLUMN cutover_date date;

-- Older plans lack an approved source identity and must be proposed again.
-- A plan may produce at most one committed activation, regardless of request key.
ALTER TABLE warehouse_activation_journals
    ADD COLUMN plan_id uuid REFERENCES warehouse_activation_plans(id);

CREATE UNIQUE INDEX uk_warehouse_activation_journal_plan
    ON warehouse_activation_journals(tenant_id, company_id, plan_id)
    WHERE plan_id IS NOT NULL;

-- A completed manifest is source evidence. Corrections arrive as new imports.
CREATE FUNCTION warehouse_reject_completed_account_entry_change() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE protected_import uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        protected_import := NEW.import_id;
    ELSE
        protected_import := OLD.import_id;
    END IF;
    IF EXISTS (SELECT 1 FROM warehouse_account_imports
               WHERE status = 'COMPLETED' AND id = protected_import) THEN
        RAISE EXCEPTION 'Completed warehouse account imports are immutable';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF EXISTS (SELECT 1 FROM warehouse_account_imports
                   WHERE id = NEW.import_id AND status = 'COMPLETED') THEN
            RAISE EXCEPTION 'Completed warehouse account imports are immutable';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER warehouse_completed_account_entry_immutable
BEFORE INSERT OR UPDATE OR DELETE ON warehouse_account_entries
FOR EACH ROW EXECUTE FUNCTION warehouse_reject_completed_account_entry_change();

CREATE FUNCTION warehouse_reject_completed_account_import_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'COMPLETED' THEN
        RAISE EXCEPTION 'Completed warehouse account manifests are immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER warehouse_completed_account_import_immutable
BEFORE UPDATE OR DELETE ON warehouse_account_imports
FOR EACH ROW EXECUTE FUNCTION warehouse_reject_completed_account_import_change();

CREATE FUNCTION warehouse_protect_approved_activation_plan() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('APPROVED', 'APPLIED') THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'Approved activation plans are immutable';
        END IF;
        IF ROW(NEW.plan_code, NEW.account_code, NEW.warehouse_id, NEW.posting_date,
               NEW.preview_hash, NEW.source_content_hash, NEW.item_count,
               NEW.total_quantity, NEW.total_amount, NEW.manifest_json,
               NEW.source_import_id, NEW.source_snapshot_date,
               NEW.source_manifest_sha256, NEW.cutover_date,
               NEW.proposed_by, NEW.proposed_at, NEW.approved_by, NEW.approved_at,
               NEW.rejection_reason)
           IS DISTINCT FROM
           ROW(OLD.plan_code, OLD.account_code, OLD.warehouse_id, OLD.posting_date,
               OLD.preview_hash, OLD.source_content_hash, OLD.item_count,
               OLD.total_quantity, OLD.total_amount, OLD.manifest_json,
               OLD.source_import_id, OLD.source_snapshot_date,
               OLD.source_manifest_sha256, OLD.cutover_date,
               OLD.proposed_by, OLD.proposed_at, OLD.approved_by, OLD.approved_at,
               OLD.rejection_reason)
           OR (OLD.status = 'APPLIED' AND NEW.status <> 'APPLIED')
           OR (OLD.status = 'APPROVED' AND NEW.status NOT IN ('APPROVED', 'APPLIED')) THEN
            RAISE EXCEPTION 'Approved activation plans are immutable';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER warehouse_approved_activation_plan_immutable
BEFORE UPDATE OR DELETE ON warehouse_activation_plans
FOR EACH ROW EXECUTE FUNCTION warehouse_protect_approved_activation_plan();
