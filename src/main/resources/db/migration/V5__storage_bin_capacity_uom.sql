ALTER TABLE storage_bins ADD COLUMN capacity_uom_id uuid;

ALTER TABLE storage_bins ADD CONSTRAINT fk_storage_bins_capacity_uom
    FOREIGN KEY (capacity_uom_id) REFERENCES unit_of_measures(id);
