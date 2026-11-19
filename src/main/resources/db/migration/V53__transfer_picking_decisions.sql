-- Preserve physical selection intent with the immutable shipment, per transfer line.
ALTER TABLE transfer_shipments
    ADD COLUMN picking_decisions jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE transfer_shipments
    ADD CONSTRAINT ck_transfer_shipment_picking_decisions_array
    CHECK (jsonb_typeof(picking_decisions) = 'array');
