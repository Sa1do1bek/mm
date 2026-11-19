CREATE SEQUENCE erp_exchange_delivery_order_seq;
ALTER TABLE erp_exchange_envelope ADD COLUMN delivery_order bigint;
WITH ordered AS (
    SELECT id, nextval('erp_exchange_delivery_order_seq') AS delivery_order
    FROM (SELECT id FROM erp_exchange_envelope ORDER BY created_at, id) existing
)
UPDATE erp_exchange_envelope e SET delivery_order=ordered.delivery_order FROM ordered WHERE e.id=ordered.id;
ALTER TABLE erp_exchange_envelope ALTER COLUMN delivery_order SET DEFAULT nextval('erp_exchange_delivery_order_seq');
ALTER TABLE erp_exchange_envelope ALTER COLUMN delivery_order SET NOT NULL;
CREATE UNIQUE INDEX uq_erp_exchange_delivery_order ON erp_exchange_envelope(delivery_order);
