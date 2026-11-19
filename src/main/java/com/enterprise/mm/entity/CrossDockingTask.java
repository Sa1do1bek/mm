package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.CrossDockingOutboundReferenceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "cross_docking_tasks")
public class CrossDockingTask extends BusinessEntity {
    @Column(name = "task_number", length = 120)
    private String taskNumber;

    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(name = "inbound_stock_movement_id")
    private UUID inboundStockMovementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outbound_reference_type", length = 80)
    private CrossDockingOutboundReferenceType outboundReferenceType;

    @Column(name = "outbound_reference_id")
    private UUID outboundReferenceId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "stock_batch_id")
    private UUID stockBatchId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "staging_location_id")
    private UUID stagingLocationId;

    @Column(name = "destination_warehouse_id")
    private UUID destinationWarehouseId;

    @Column(name = "destination_storage_location_id")
    private UUID destinationStorageLocationId;

    @Column(nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "outbound_stock_movement_id")
    private UUID outboundStockMovementId;

    @Column(name = "planned_ship_at")
    private LocalDateTime plannedShipAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
