package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MaterialUnitEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_unit_events")
public class MaterialUnitEvent extends BusinessEntity {
    @Column(name = "material_unit_id", nullable = false, updatable = false)
    private UUID materialUnitId;
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 40, nullable = false, updatable = false)
    private MaterialUnitEventType eventType;
    @Column(name = "source_type", length = 80, nullable = false, updatable = false)
    private String sourceType;
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;
    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;
    @Column(name = "storage_location_id", updatable = false)
    private UUID storageLocationId;
    @Column(name = "storage_bin_id", updatable = false)
    private UUID storageBinId;
    @Column(name = "stock_batch_id", updatable = false)
    private UUID stockBatchId;
    @Column(name = "performed_by", length = 120, updatable = false)
    private String performedBy;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;
}
