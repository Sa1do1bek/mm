package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "storage_bins",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_storage_bins_tenant_location_bin",
        columnNames = {"tenant_id", "storage_location_id", "bin_code"}
    )
)
public class StorageBin extends BusinessEntity {
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "storage_location_id", nullable = false)
    private UUID storageLocationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id", insertable = false, updatable = false)
    private StorageLocation storageLocation;

    @Column(name = "bin_code", nullable = false, length = 80)
    private String binCode;

    @Column(length = 160)
    private String barcode;

    @Column(length = 160)
    private String qrCode;

    @Column(length = 160)
    private String rfidTag;

    @Column(precision = 22, scale = 7)
    private BigDecimal capacityQuantity;

    @Column(name = "capacity_uom_id")
    private UUID capacityUomId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "capacity_uom_id", insertable = false, updatable = false)
    private UnitOfMeasure capacityUom;

    @Column(precision = 19, scale = 4)
    private BigDecimal maxWeight;

    private boolean allowMixedMaterials = true;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        if (warehouse != null) {
            warehouseId = warehouse.getId();
        }
        if (storageLocation != null) {
            storageLocationId = storageLocation.getId();
            warehouseId = storageLocation.getWarehouseId();
        }
        if ((binCode == null || binCode.isBlank()) && getCode() != null) {
            binCode = getCode();
        }
    }
}
