-- Presentation/report ledger only. Never insert historical postings into stock_movements:
-- their quantities and costs are already represented by the exact opening snapshot.
CREATE TABLE warehouse_history_projection_state (
 tenant_id varchar(80) NOT NULL, company_id uuid NOT NULL, stage varchar(20) NOT NULL,
 stock_state jsonb NOT NULL, PRIMARY KEY(tenant_id,company_id,stage)
);
CREATE TABLE warehouse_imported_movements (
 id uuid PRIMARY KEY, tenant_id varchar(80) NOT NULL, company_id uuid NOT NULL,
 document_key varchar(180) NOT NULL, movement_date date NOT NULL,
 document_number text, document_type text, material_id uuid, warehouse_id uuid,
 material_source_id text NOT NULL, warehouse_source_id text NOT NULL,
 material_name text, warehouse_name text, material_code text, warehouse_code text, quantity numeric NOT NULL, amount numeric NOT NULL,
 source_record_id uuid NOT NULL, source_records bigint NOT NULL,
 target_warehouse_id uuid, movement_type varchar(30) NOT NULL DEFAULT 'ADJUSTMENT'
);

-- Fingerprints cover actual current quantities, reservations, batches, costs and postings.
-- Store hashes only; no credentials or personal records are included.
DO $$
DECLARE scope record; tab text; state jsonb; fingerprint jsonb;
BEGIN
 FOR scope IN SELECT DISTINCT tenant_id,company_id FROM warehouse_history_runs WHERE status='COMPLETED' LOOP
  state='{}'::jsonb;
  FOREACH tab IN ARRAY ARRAY['stock_balances','stock_batches','inventory_cost_layers','stock_cost_movements','stock_movements','stock_movement_allocations','stock_movement_allocation_cost_layers'] LOOP
   IF to_regclass(tab) IS NOT NULL THEN
    EXECUTE format('select jsonb_build_object(''rows'',count(*),''md5'',md5(coalesce(string_agg(md5(row_to_json(t)::text),'''' order by id),''''))) from %I t where tenant_id=$1 and company_id=$2',tab)
      INTO fingerprint USING scope.tenant_id,scope.company_id;
    state=state||jsonb_build_object(tab,fingerprint);
   END IF;
  END LOOP;
  INSERT INTO warehouse_history_projection_state VALUES(scope.tenant_id,scope.company_id,'BEFORE',state);
 END LOOP;
END $$;

WITH legs AS (
 SELECT r.*,r.content::jsonb body FROM warehouse_history_records r
 JOIN warehouse_history_runs run ON run.id=r.import_id AND run.tenant_id=r.tenant_id AND run.company_id=r.company_id AND run.status='COMPLETED'
 WHERE r.kind='MOVEMENT' AND r.source_table='_accrg4272' AND r.posted=true
   AND r.direction IN ('DT','CT') AND r.document_date<timestamp '2026-10-09'
), physical AS (
 SELECT * FROM legs WHERE body->'data'->>'accountCode' ~ '^10[0-9]{2}$'
), net AS (
 SELECT tenant_id,company_id,document_key,document_date::date movement_date,
  material_id,warehouse_id,body->>'materialSourceId' material_source_id,body->>'warehouseSourceId' warehouse_source_id,
  sum(quantity::numeric) quantity,sum(book_value::numeric) amount,
  min(id::text)::uuid source_record_id,count(*) source_records
 FROM physical
 GROUP BY tenant_id,company_id,document_key,document_date::date,material_id,warehouse_id,body->>'materialSourceId',body->>'warehouseSourceId'
 HAVING sum(quantity::numeric)<>0 OR sum(book_value::numeric)<>0
)
INSERT INTO warehouse_imported_movements(id,tenant_id,company_id,document_key,movement_date,document_number,document_type,
 material_id,warehouse_id,material_source_id,warehouse_source_id,material_name,warehouse_name,material_code,warehouse_code,quantity,amount,source_record_id,source_records)
SELECT md5(concat_ws(':','1c-physical',n.tenant_id,n.company_id,n.document_key,n.movement_date,n.material_source_id,n.warehouse_source_id))::uuid,
 n.tenant_id,n.company_id,n.document_key,n.movement_date,coalesce(d.document_number,'1C'),coalesce(d.document_type,'1C'),
 n.material_id,n.warehouse_id,n.material_source_id,n.warehouse_source_id,
 coalesce(m.name,mr.content::jsonb->'data'->>'name',n.material_source_id),
 coalesce(w.name,wr.content::jsonb->'data'->>'name',n.warehouse_source_id),coalesce(m.code,mr.content::jsonb->'data'->>'code',n.material_source_id),coalesce(w.code,wr.content::jsonb->'data'->>'code',n.warehouse_source_id),n.quantity,n.amount,n.source_record_id,n.source_records
FROM net n
LEFT JOIN warehouse_history_records d ON d.tenant_id=n.tenant_id AND d.company_id=n.company_id AND d.document_key=n.document_key AND d.kind='DOCUMENT'
LEFT JOIN materials m ON m.id=n.material_id AND m.tenant_id=n.tenant_id AND m.company_id=n.company_id
LEFT JOIN warehouses w ON w.id=n.warehouse_id AND w.tenant_id=n.tenant_id AND w.company_id=n.company_id
LEFT JOIN warehouse_history_records mr ON mr.tenant_id=n.tenant_id AND mr.company_id=n.company_id AND mr.source_key='reference:materials:'||n.material_source_id
LEFT JOIN warehouse_history_records wr ON wr.tenant_id=n.tenant_id AND wr.company_id=n.company_id AND wr.source_key='reference:warehouses:'||n.warehouse_source_id;

CREATE INDEX warehouse_imported_date ON warehouse_imported_movements(tenant_id,company_id,movement_date DESC,id);
CREATE INDEX warehouse_imported_material ON warehouse_imported_movements(tenant_id,company_id,material_id,movement_date);
CREATE INDEX warehouse_imported_warehouse ON warehouse_imported_movements(tenant_id,company_id,warehouse_id,movement_date);
CREATE INDEX warehouse_imported_document ON warehouse_imported_movements(tenant_id,company_id,document_key,material_source_id,movement_date);

-- The new table has no committed statistics yet. Collect them before pairing so
-- PostgreSQL chooses the document index instead of rescanning every day's rows.
ANALYZE warehouse_imported_movements;

-- Opposite warehouse is reported only when the original document makes it unambiguous.
UPDATE warehouse_imported_movements h SET target_warehouse_id=(
 SELECT (array_agg(DISTINCT o.warehouse_id))[1] FROM warehouse_imported_movements o
 WHERE o.tenant_id=h.tenant_id AND o.company_id=h.company_id AND o.document_key=h.document_key
 AND o.material_source_id=h.material_source_id AND o.movement_date=h.movement_date
 AND o.warehouse_source_id<>h.warehouse_source_id AND sign(o.quantity)=-sign(h.quantity)
 HAVING count(DISTINCT o.warehouse_id)=1
),movement_type=CASE
 WHEN h.document_type ILIKE '%Перемещ%' THEN 'TRANSFER'
 WHEN h.document_type ILIKE '%Списание%' THEN 'WRITE_OFF'
 WHEN h.document_type ILIKE '%Возврат%' THEN 'RETURN'
 WHEN h.quantity>0 THEN 'RECEIPT' WHEN h.quantity<0 THEN 'ISSUE' ELSE 'ADJUSTMENT' END;
ANALYZE warehouse_imported_movements;

DO $$
DECLARE scope record; tab text; state jsonb; fingerprint jsonb;
BEGIN
 FOR scope IN SELECT DISTINCT tenant_id,company_id FROM warehouse_history_runs WHERE status='COMPLETED' LOOP
  state='{}'::jsonb;
  FOREACH tab IN ARRAY ARRAY['stock_balances','stock_batches','inventory_cost_layers','stock_cost_movements','stock_movements','stock_movement_allocations','stock_movement_allocation_cost_layers'] LOOP
   IF to_regclass(tab) IS NOT NULL THEN
    EXECUTE format('select jsonb_build_object(''rows'',count(*),''md5'',md5(coalesce(string_agg(md5(row_to_json(t)::text),'''' order by id),''''))) from %I t where tenant_id=$1 and company_id=$2',tab)
      INTO fingerprint USING scope.tenant_id,scope.company_id;
    state=state||jsonb_build_object(tab,fingerprint);
   END IF;
  END LOOP;
  INSERT INTO warehouse_history_projection_state VALUES(scope.tenant_id,scope.company_id,'AFTER',state);
 END LOOP;
END $$;
