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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_reservations")
public class MaterialReservation extends BusinessEntity {
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

    @Column(name = "demand_plan_id")
    private UUID demandPlanId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demand_plan_id", insertable = false, updatable = false)
    private DemandPlan demandPlan;

    @Column(name = "demand_plan_item_id")
    private UUID demandPlanItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demand_plan_item_id", insertable = false, updatable = false)
    private DemandPlanItem demandPlanItem;

    @Column(name = "project_code", length = 80)
    private String projectCode;

    @Column(name = "repair_order_code", length = 80)
    private String repairOrderCode;

    @Column(name = "reservation_type", nullable = false, length = 30)
    private String reservationType;

    @Column(nullable = false, length = 30)
    private String priority;

    @Column(nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "reserved_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal reservedQuantity = BigDecimal.ZERO;

    @Column(name = "released_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal releasedQuantity = BigDecimal.ZERO;

    @Column(name = "required_date")
    private LocalDate requiredDate;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "reference_type", length = 80)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reserved_by", length = 120)
    private String reservedBy;

    @Column(name = "disposition_snapshot", length = 40)
    private String dispositionSnapshot;

    @Column(name = "uom_id_snapshot")
    private UUID uomIdSnapshot;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uom_id_snapshot", insertable = false, updatable = false)
    private UnitOfMeasure uomSnapshot;

    @Column(name = "uom_code_snapshot", length = 80)
    private String uomCodeSnapshot;

    @Column(name = "consumed_quantity", precision = 22, scale = 7)
    private BigDecimal consumedQuantity = BigDecimal.ZERO;

    @Column(name = "cancelled_quantity", precision = 22, scale = 7)
    private BigDecimal cancelledQuantity = BigDecimal.ZERO;

    @Column(name = "expired_quantity", precision = 22, scale = 7)
    private BigDecimal expiredQuantity = BigDecimal.ZERO;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;
    @Column(name = "released_by", length = 120)
    private String releasedBy;
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;
    @Column(name = "consumed_by", length = 120)
    private String consumedBy;
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
    @Column(name = "cancelled_by", length = 120)
    private String cancelledBy;
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;
    @Column(name = "expired_at")
    private LocalDateTime expiredAt;
    @Column(name = "expired_by", length = 120)
    private String expiredBy;
    @Column(name = "expiration_reason", length = 500)
    private String expirationReason;
    @Column(name = "terminal_at")
    private LocalDateTime terminalAt;
    @Column(name = "terminal_by", length = 120)
    private String terminalBy;
    @Column(name = "terminal_reason", length = 500)
    private String terminalReason;
    @Column(name = "terminal_evidence_reference", length = 500)
    private String terminalEvidenceReference;
    @Column(name = "terminal_idempotency_key", length = 160)
    private String terminalIdempotencyKey;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        materialId = material == null ? materialId : material.getId();
        warehouseId = warehouse == null ? warehouseId : warehouse.getId();
        storageLocationId = storageLocation == null ? storageLocationId : storageLocation.getId();
        storageBinId = storageBin == null ? storageBinId : storageBin.getId();
        stockBatchId = stockBatch == null ? stockBatchId : stockBatch.getId();
        demandPlanId = demandPlan == null ? demandPlanId : demandPlan.getId();
        demandPlanItemId = demandPlanItem == null ? demandPlanItemId : demandPlanItem.getId();
        uomIdSnapshot = uomSnapshot == null ? uomIdSnapshot : uomSnapshot.getId();
    }
}
