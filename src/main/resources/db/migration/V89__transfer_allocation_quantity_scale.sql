ALTER TABLE transfer_shipment_allocations DROP CONSTRAINT ck_transfer_allocation_quantity;
ALTER TABLE transfer_shipment_allocations ADD CONSTRAINT ck_transfer_allocation_quantity
    CHECK (dispatched_quantity > 0 AND quantity_scale BETWEEN 0 AND 7);
