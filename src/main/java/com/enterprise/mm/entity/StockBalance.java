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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stock_balances", uniqueConstraints = @UniqueConstraint(name = "uk_stock_balance_key", columnNames = {"tenant_id", "company_id", "material_id", "warehouse_id", "storage_location_id", "storage_bin_id", "stock_batch_id", "owner_party_id", "stock_category"}))
public class StockBalance extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;
    @Column(name = "storage_location_id")
    private UUID storageLocationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id", insertable = false, updatable = false)
    private StorageLocation storageLocation;
    @Column(name = "storage_bin_id")
    private UUID storageBinId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_bin_id", insertable = false, updatable = false)
    private StorageBin storageBin;
    @Column(name = "stock_batch_id")
    private UUID stockBatchId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_batch_id", insertable = false, updatable = false)
    private StockBatch stockBatch;
    @Column(name = "owner_party_id")
    private UUID ownerPartyId;
    @Column(name = "stock_category", nullable = false, length = 30)
    private String stockCategory = "OWNED";
    @Column(nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;
    @Column(nullable = false, precision = 22, scale = 7)
    private BigDecimal availableQuantity = BigDecimal.ZERO;
    @Column(nullable = false, precision = 22, scale = 7)
    private BigDecimal reservedQuantity = BigDecimal.ZERO;
    @Column(nullable = false, precision = 22, scale = 7)
    private BigDecimal quarantineQuantity = BigDecimal.ZERO;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalValue = BigDecimal.ZERO;
    @Column(name = "balance_at")
    private LocalDateTime balanceAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        if (material != null) {
            materialId = material.getId();
        }
        if (warehouse != null) {
            warehouseId = warehouse.getId();
        }
        storageLocationId = storageLocation == null ? storageLocationId : storageLocation.getId();
        storageBinId = storageBin == null ? storageBinId : storageBin.getId();
        stockBatchId = stockBatch == null ? stockBatchId : stockBatch.getId();
    }
}
