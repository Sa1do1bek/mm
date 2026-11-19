ALTER TABLE inventory_documents ADD COLUMN stock_as_of_date date;
ALTER TABLE inventory_items ADD COLUMN as_of_quantity numeric(22,7);
ALTER TABLE inventory_items ADD COLUMN as_of_value numeric(19,4);
