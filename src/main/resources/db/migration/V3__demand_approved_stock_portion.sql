-- Reservation is temporary execution state; it cannot represent the stock-review approval.
-- Existing reductions are not presumed to be stock allocations.
ALTER TABLE demand_plan_items ADD COLUMN stock_fulfillment_quantity numeric(19,4) NOT NULL DEFAULT 0;
ALTER TABLE demand_plan_items ADD CONSTRAINT ck_demand_stock_portion CHECK
 (stock_fulfillment_quantity >= 0 AND stock_fulfillment_quantity + approved_quantity <= requested_quantity);
