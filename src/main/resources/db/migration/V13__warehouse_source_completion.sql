-- Addenda share the original snapshot but retain separate immutable manifests.
DO $$ DECLARE item record; BEGIN
 FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='warehouse_account_imports'::regclass
  AND contype='u' AND pg_get_constraintdef(oid) LIKE '%organization_source_id, snapshot_date%'
 LOOP EXECUTE format('ALTER TABLE warehouse_account_imports DROP CONSTRAINT %I',item.conname); END LOOP;
END $$;
CREATE UNIQUE INDEX warehouse_account_manifest_identity ON warehouse_account_imports(tenant_id,company_id,manifest_sha256);

-- Mutable reference bindings never rewrite the imported source content or hashes.
CREATE TABLE warehouse_source_master_links (
 tenant_id varchar(80) NOT NULL, company_id uuid NOT NULL, reference_kind varchar(20) NOT NULL,
 reference_type varchar(8) NOT NULL, source_id varchar(32) NOT NULL, master_id uuid NOT NULL,
 source_record_id uuid NOT NULL REFERENCES warehouse_history_records(id), source_type varchar(30), source_material_type text,
 PRIMARY KEY(tenant_id,company_id,reference_kind,reference_type,source_id)
);
CREATE TABLE warehouse_source_profiles (
 id uuid PRIMARY KEY REFERENCES warehouse_history_records(id), tenant_id varchar(80) NOT NULL, company_id uuid NOT NULL,
 category varchar(40) NOT NULL, source_table varchar(80) NOT NULL, source_id varchar(80) NOT NULL,
 target_id uuid, parent_source_id varchar(80), code text, name text NOT NULL,
 source_date timestamp, search_text text NOT NULL, fields jsonb NOT NULL,
 UNIQUE(tenant_id,company_id,category,source_table,source_id)
);
CREATE INDEX warehouse_source_profile_list ON warehouse_source_profiles(tenant_id,company_id,category,code,id);
CREATE INDEX warehouse_source_profile_target ON warehouse_source_profiles(tenant_id,company_id,target_id,category);
ALTER TABLE material_groups ADD COLUMN parent_id uuid;
ALTER TABLE materials ADD COLUMN source_data jsonb;
ALTER TABLE warehouses ADD COLUMN source_data jsonb;
ALTER TABLE warehouses ADD COLUMN parent_id uuid;
ALTER TABLE warehouses ADD COLUMN source_group boolean NOT NULL DEFAULT false;
ALTER TABLE warehouses ADD COLUMN source_has_balance boolean NOT NULL DEFAULT false;

ALTER TABLE materials DROP CONSTRAINT IF EXISTS materials_subtype_business_type_check;
ALTER TABLE materials ADD CONSTRAINT materials_subtype_business_type_check CHECK (material_subtype IS NULL OR (material_type='TMB' AND material_subtype IN ('MATERIAL','EQUIPMENT','ANIMALS','FINISHED_GOODS','SEMI_FINISHED_GOODS','GOODS')));
CREATE INDEX warehouse_source_profile_parent ON warehouse_source_profiles(tenant_id,company_id,category,parent_source_id);
