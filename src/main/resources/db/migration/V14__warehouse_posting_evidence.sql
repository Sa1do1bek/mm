-- Additive warehouse subledger evidence. No existing stock writer is switched on by this migration.
-- The financial general ledger remains in 1C/FI under the engineering D01 choice.
CREATE TABLE warehouse_posting_rules (
 id uuid PRIMARY KEY,
 tenant_id varchar(80) NOT NULL,
 company_id uuid NOT NULL,
 book varchar(40) NOT NULL,
 operation varchar(80) NOT NULL,
 context_key varchar(160) NOT NULL,
 rule_version integer NOT NULL CHECK (rule_version > 0),
 effective_from date NOT NULL,
 effective_through date,
 approved boolean NOT NULL DEFAULT false,
 account_code varchar(40) NOT NULL,
 currency_code varchar(3) NOT NULL,
 rule_snapshot jsonb NOT NULL,
 rule_hash varchar(64) NOT NULL CHECK (rule_hash ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT warehouse_posting_rule_period CHECK (effective_through IS NULL OR effective_through >= effective_from),
 CONSTRAINT warehouse_posting_rule_version UNIQUE (tenant_id,company_id,book,operation,context_key,rule_version),
 CONSTRAINT warehouse_posting_rule_scoped_id UNIQUE (id,tenant_id,company_id,book,operation,context_key,rule_version)
);
CREATE INDEX warehouse_posting_rule_lookup ON warehouse_posting_rules
 (tenant_id,company_id,book,operation,context_key,effective_from,effective_through)
 WHERE approved;

CREATE TABLE warehouse_posting_batches (
 id uuid PRIMARY KEY,
 tenant_id varchar(80) NOT NULL,
 company_id uuid NOT NULL,
 book varchar(40) NOT NULL,
 operation varchar(80) NOT NULL,
 context_key varchar(160) NOT NULL,
 resource_id uuid NOT NULL,
 idempotency_key varchar(160) NOT NULL,
 request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
 posting_date date NOT NULL,
 expected_version bigint NOT NULL CHECK (expected_version >= 0),
 rule_id uuid NOT NULL,
 rule_version integer NOT NULL,
 rule_snapshot jsonb NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMMITTED')),
 receipt_json jsonb,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 committed_at timestamptz,
 CONSTRAINT warehouse_posting_batch_key CHECK (idempotency_key = btrim(idempotency_key) AND idempotency_key <> ''),
 CONSTRAINT warehouse_posting_batch_receipt CHECK (
  (status = 'PENDING' AND receipt_json IS NULL AND committed_at IS NULL)
  OR (status = 'COMMITTED' AND receipt_json IS NOT NULL AND committed_at IS NOT NULL)
 ),
 CONSTRAINT warehouse_posting_batch_identity UNIQUE (tenant_id,company_id,operation,resource_id,idempotency_key),
 CONSTRAINT warehouse_posting_batch_rule_scope FOREIGN KEY
  (rule_id,tenant_id,company_id,book,operation,context_key,rule_version)
  REFERENCES warehouse_posting_rules(id,tenant_id,company_id,book,operation,context_key,rule_version)
);
CREATE INDEX warehouse_posting_batch_date ON warehouse_posting_batches(tenant_id,company_id,posting_date,id);

CREATE TABLE warehouse_posting_effects (
 batch_id uuid NOT NULL REFERENCES warehouse_posting_batches(id),
 effect_sequence integer NOT NULL CHECK (effect_sequence > 0),
 effect_type varchar(20) NOT NULL CHECK (effect_type IN ('STOCK','ACCOUNT','COST','VALUE_ONLY')),
 material_id uuid NOT NULL,
 warehouse_id uuid,
 storage_location_id uuid,
 storage_bin_id uuid,
 stock_batch_id uuid,
 owner_id uuid,
 stock_category varchar(40),
 disposition varchar(40),
 account_code varchar(40),
 base_unit_code varchar(40),
 quantity numeric(22,7) NOT NULL DEFAULT 0,
 amount numeric(19,4) NOT NULL DEFAULT 0,
 currency_code varchar(3),
 source_reference text,
 PRIMARY KEY (batch_id,effect_sequence),
 CONSTRAINT warehouse_posting_effect_quantity_unit CHECK (quantity = 0 OR base_unit_code IS NOT NULL),
 CONSTRAINT warehouse_posting_effect_amount_currency CHECK (amount = 0 OR currency_code IS NOT NULL),
 CONSTRAINT warehouse_posting_effect_kind CHECK (
  (effect_type = 'STOCK' AND warehouse_id IS NOT NULL AND base_unit_code IS NOT NULL
   AND quantity <> 0 AND amount = 0)
  OR (effect_type = 'ACCOUNT' AND account_code IS NOT NULL AND (quantity <> 0 OR amount <> 0))
  OR (effect_type = 'COST' AND amount <> 0)
  OR (effect_type = 'VALUE_ONLY' AND account_code IS NOT NULL AND currency_code IS NOT NULL
   AND base_unit_code IS NULL AND quantity = 0 AND amount <> 0)
 )
);
CREATE INDEX warehouse_posting_effect_position ON warehouse_posting_effects
 (material_id,warehouse_id,account_code,owner_id,stock_category);
