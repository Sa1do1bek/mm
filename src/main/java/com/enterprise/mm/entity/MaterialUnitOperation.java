package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MaterialUnitOperationType;
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
@Table(name = "material_unit_operations")
public class MaterialUnitOperation extends BusinessEntity {
    @Column(name = "material_unit_id", nullable = false, updatable = false)
    private UUID materialUnitId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, updatable = false, length = 40)
    private MaterialUnitOperationType operationType;

    @Column(name = "reference_type", nullable = false, updatable = false, length = 80)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private UUID referenceId;

    @Column(name = "reference_line_id", updatable = false)
    private UUID referenceLineId;

    @Column(name = "from_status", updatable = false, length = 40)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, updatable = false, length = 40)
    private String toStatus;

    @Column(name = "from_warehouse_id", updatable = false)
    private UUID fromWarehouseId;

    @Column(name = "to_warehouse_id", updatable = false)
    private UUID toWarehouseId;

    @Column(name = "from_storage_location_id", updatable = false)
    private UUID fromStorageLocationId;

    @Column(name = "to_storage_location_id", updatable = false)
    private UUID toStorageLocationId;

    @Column(name = "from_storage_bin_id", updatable = false)
    private UUID fromStorageBinId;

    @Column(name = "to_storage_bin_id", updatable = false)
    private UUID toStorageBinId;

    @Column(name = "stock_batch_id", updatable = false)
    private UUID stockBatchId;

    @Column(name = "operation_key", updatable = false, length = 160)
    private String operationKey;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;
}
