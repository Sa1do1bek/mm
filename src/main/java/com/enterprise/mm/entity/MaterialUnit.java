package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MaterialUnitStatus;
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
@Table(name = "material_units")
public class MaterialUnit extends BusinessEntity {
    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;
    @Column(name = "manufacturer_serial_number", length = 160, nullable = false, updatable = false)
    private String manufacturerSerialNumber;
    @Column(name = "normalized_serial_number", length = 160, nullable = false, updatable = false)
    private String normalizedSerialNumber;
    @Column(name = "internal_unit_number", length = 120, updatable = false)
    private String internalUnitNumber;
    @Enumerated(EnumType.STRING)
    @Column(name = "unit_status", length = 40, nullable = false)
    private MaterialUnitStatus unitStatus = MaterialUnitStatus.IN_STOCK;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Column(name = "storage_location_id")
    private UUID storageLocationId;
    @Column(name = "storage_bin_id")
    private UUID storageBinId;
    @Column(name = "stock_batch_id")
    private UUID stockBatchId;
    @Column(name = "goods_receipt_line_id", updatable = false)
    private UUID goodsReceiptLineId;
    @Column(name = "goods_receipt_unit_id", updatable = false)
    private UUID goodsReceiptUnitId;
    @Column(name = "manual_stock_movement_id", updatable = false)
    private UUID manualStockMovementId;
    @Column(name = "production_stock_movement_id", updatable = false)
    private UUID productionStockMovementId;
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;
}
