package com.enterprise.mm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@Entity
@Table(name = "stock_movement_allocations")
public class StockMovementAllocation {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 80, updatable = false)
    private String tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 120, updatable = false)
    private String createdBy;

    @Column(name = "movement_id", nullable = false, updatable = false)
    private UUID movementId;

    @Column(name = "allocation_sequence", nullable = false, updatable = false)
    private int allocationSequence;

    @Column(name = "allocation_role", nullable = false, length = 40, updatable = false)
    private String allocationRole;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "balance_id", updatable = false)
    private UUID balanceId;

    @Column(name = "source_warehouse_id_snapshot", updatable = false)
    private UUID sourceWarehouseIdSnapshot;

    @Column(name = "source_storage_location_id_snapshot", updatable = false)
    private UUID sourceStorageLocationIdSnapshot;

    @Column(name = "source_storage_bin_id_snapshot", updatable = false)
    private UUID sourceStorageBinIdSnapshot;

    @Column(name = "source_stock_batch_id_snapshot", updatable = false)
    private UUID sourceStockBatchIdSnapshot;

    @Column(name = "source_batch_number_snapshot", length = 120, updatable = false)
    private String sourceBatchNumberSnapshot;

    @Column(name = "source_disposition_snapshot", length = 40, updatable = false)
    private String sourceDispositionSnapshot;

    @Column(name = "target_warehouse_id_snapshot", updatable = false)
    private UUID targetWarehouseIdSnapshot;

    @Column(name = "target_storage_location_id_snapshot", updatable = false)
    private UUID targetStorageLocationIdSnapshot;

    @Column(name = "target_storage_bin_id_snapshot", updatable = false)
    private UUID targetStorageBinIdSnapshot;

    @Column(name = "target_stock_batch_id_snapshot", updatable = false)
    private UUID targetStockBatchIdSnapshot;

    @Column(name = "target_batch_number_snapshot", length = 120, updatable = false)
    private String targetBatchNumberSnapshot;

    @Column(name = "target_disposition_snapshot", length = 40, updatable = false)
    private String targetDispositionSnapshot;

    @Column(name = "uom_id_snapshot", updatable = false)
    private UUID uomIdSnapshot;

    @Column(name = "uom_code_snapshot", length = 80, updatable = false)
    private String uomCodeSnapshot;

    @Column(name = "on_hand_delta", nullable = false, precision = 22, scale = 7, updatable = false)
    private BigDecimal onHandDelta;

    @Column(name = "available_delta", nullable = false, precision = 22, scale = 7, updatable = false)
    private BigDecimal availableDelta;

    @Column(name = "reserved_delta", nullable = false, precision = 22, scale = 7, updatable = false)
    private BigDecimal reservedDelta;

    @Column(name = "quarantine_delta", nullable = false, precision = 22, scale = 7, updatable = false)
    private BigDecimal quarantineDelta;

    @Column(name = "total_value_delta", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal totalValueDelta;

    @Column(name = "batch_quantity_delta", nullable = false, precision = 22, scale = 7, updatable = false)
    private BigDecimal batchQuantityDelta;

    @Column(name = "unit_cost_snapshot", precision = 19, scale = 6, updatable = false)
    private BigDecimal unitCostSnapshot;

    @Column(name = "currency_code_snapshot", length = 10, updatable = false)
    private String currencyCodeSnapshot;

    @Column(name = "exchange_rate_snapshot", precision = 19, scale = 6, updatable = false)
    private BigDecimal exchangeRateSnapshot;

    @Column(name = "source_inventory_item_id", updatable = false)
    private UUID sourceInventoryItemId;

    @PrePersist
    void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
