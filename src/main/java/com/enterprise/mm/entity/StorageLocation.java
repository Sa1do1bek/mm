package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.StorageZoneType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "storage_locations")
public class StorageLocation extends BusinessEntity {
    @Column(name = "warehouse_id")
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;
    @Column(length = 80)
    private String zoneCode;
    @Column(length = 80)
    private String binCode;
    @Column(length = 160)
    private String barcode;
    @Column(length = 160)
    private String qrCode;
    @Column(length = 160)
    private String rfidTag;
    @Enumerated(EnumType.STRING)
    @Column(length = 80)
    private StorageZoneType storageZoneType;
    @Column(length = 80)
    private String temperatureZone;
    @Column(length = 40)
    private String hazardClassAllowed;
    @Column(precision = 22, scale = 7)
    private BigDecimal capacityQuantity;
    @Column(precision = 19, scale = 4)
    private BigDecimal maxWeight;
    private boolean allowMixedMaterials = true;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        if (warehouse != null) {
            warehouseId = warehouse.getId();
        }
    }
}
