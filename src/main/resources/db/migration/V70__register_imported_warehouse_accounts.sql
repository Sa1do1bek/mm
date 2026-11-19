-- Completed accounting snapshots are source evidence. Their account codes also
-- need catalog records so Finance can manage the current names and status.
WITH imported AS (
    SELECT DISTINCT ON (e.tenant_id, e.company_id, e.account_code)
        e.tenant_id, e.company_id, e.account_code,
        e.account_name, e.account_source_id
    FROM warehouse_account_entries e
    JOIN warehouse_account_imports r
      ON r.id = e.import_id
     AND r.tenant_id = e.tenant_id
     AND r.company_id = e.company_id
     AND r.status = 'COMPLETED'
    ORDER BY e.tenant_id, e.company_id, e.account_code,
             r.snapshot_date DESC, r.completed_at DESC, e.source_seq
)
INSERT INTO chart_of_accounts
    (id, tenant_id, company_id, created_at, version, is_delete, code, account_code,
     name, status, is_group, source_system, external_id, last_synced_at)
SELECT gen_random_uuid(), tenant_id, company_id, CURRENT_TIMESTAMP, 0, false,
       account_code, account_code, account_name, 'ACTIVE', false,
       'ONE_C', account_source_id, CURRENT_TIMESTAMP
FROM imported
ON CONFLICT (tenant_id, company_id, account_code) DO UPDATE
    SET is_delete = false, deleted_at = null, updated_at = CURRENT_TIMESTAMP,
        version = chart_of_accounts.version + 1
WHERE chart_of_accounts.is_delete = true;
