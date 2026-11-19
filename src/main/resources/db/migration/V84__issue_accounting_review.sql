-- Preserve every posted physical issue until its accounting is explicitly resolved.
CREATE TABLE stock_issue_account_reviews (
    id uuid PRIMARY KEY,
    tenant_id varchar(80) NOT NULL,
    company_id uuid NOT NULL,
    movement_id uuid NOT NULL REFERENCES stock_movements(id),
    status varchar(24) NOT NULL DEFAULT 'PENDING_REVIEW',
    stock_account_code varchar(40),
    counter_account_code varchar(40),
    reviewed_by varchar(120),
    reviewed_at timestamp,
    review_note text,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stock_issue_account_review UNIQUE (tenant_id, company_id, movement_id),
    CONSTRAINT ck_stock_issue_account_review_status CHECK (
        (status = 'PENDING_REVIEW' AND stock_account_code IS NULL AND counter_account_code IS NULL
          AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR (status = 'RESOLVED' AND stock_account_code IS NOT NULL AND counter_account_code IS NOT NULL
          AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);
CREATE INDEX ix_stock_issue_account_review_queue
    ON stock_issue_account_reviews(tenant_id,company_id,status,created_at,id);

CREATE FUNCTION register_stock_issue_account_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'POSTED' AND NEW.posted_at IS NOT NULL
       AND NEW.movement_type IN ('ISSUE','WRITE_OFF')
       AND NEW.reversal_of_id IS NULL AND NOT NEW.is_delete THEN
        INSERT INTO stock_issue_account_reviews(id,tenant_id,company_id,movement_id)
        VALUES (gen_random_uuid(),NEW.tenant_id,NEW.company_id,NEW.id)
        ON CONFLICT (tenant_id,company_id,movement_id) DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER stock_issue_account_review_after_post
AFTER INSERT OR UPDATE ON stock_movements
FOR EACH ROW EXECUTE FUNCTION register_stock_issue_account_review();

-- Preserve existing posted issues as unresolved until their accounts are reviewed.
INSERT INTO stock_issue_account_reviews(id,tenant_id,company_id,movement_id)
SELECT gen_random_uuid(),m.tenant_id,m.company_id,m.id
FROM stock_movements m
WHERE m.status IN ('POSTED','CANCELLED') AND m.posted_at IS NOT NULL AND NOT m.is_delete
  AND m.movement_type IN ('ISSUE','WRITE_OFF') AND m.reversal_of_id IS NULL
ON CONFLICT (tenant_id,company_id,movement_id) DO NOTHING;
