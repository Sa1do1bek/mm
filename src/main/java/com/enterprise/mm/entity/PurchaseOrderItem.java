package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
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
@Table(name = "purchase_order_items")
public class PurchaseOrderItem extends BusinessEntity {
    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;
    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, length = 20)
    private ProcurementLineType lineType = ProcurementLineType.MATERIAL;
    @Column(name = "material_id")
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;
    @Enumerated(EnumType.STRING)
    @Column(name = "material_type_snapshot", length = 30)
    private MaterialType materialTypeSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(name = "tracking_mode_snapshot", length = 20)
    private TrackingMode trackingModeSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(name = "batch_tracking_snapshot", length = 20)
    private BatchTrackingPolicy batchTrackingSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(name = "serial_tracking_snapshot", length = 20)
    private SerialTrackingPolicy serialTrackingSnapshot;
    @Column(precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal unitPrice = BigDecimal.ZERO;
    @Column(name = "service_request_id")
    private UUID serviceRequestId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_request_id", insertable = false, updatable = false)
    private ServiceRequest serviceRequest;
    @Column(name = "service_order_id")
    private UUID serviceOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_order_id", insertable = false, updatable = false)
    private ServiceOrder serviceOrder;
    @Column(name = "service_act_id")
    private UUID serviceActId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_act_id", insertable = false, updatable = false)
    private ServiceAcceptanceAct serviceAct;
    @Column(name = "service_description", columnDefinition = "text")
    private String serviceDescription;
    @Column(name = "service_category", length = 120)
    private String serviceCategory;
    @Column(length = 80)
    private String unit;
    @Column(length = 120)
    private String period;
    @Column(length = 120)
    private String stage;
    @Column(name = "budget_item_id")
    private UUID budgetItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_item_id", insertable = false, updatable = false)
    private BudgetItem budgetItem;
    @Column(precision = 22, scale = 7)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;
    @Column(name = "accepted_quantity", precision = 22, scale = 7)
    private BigDecimal acceptedQuantity = BigDecimal.ZERO;
    @Column(precision = 22, scale = 7)
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;
    @Column(precision = 22, scale = 7)
    private BigDecimal damagedQuantity = BigDecimal.ZERO;
    @Column(name = "shortage_reason", columnDefinition = "text")
    private String shortageReason;
    @Column(name = "receipt_batch_number", length = 120)
    private String receiptBatchNumber;
    @Column(name = "receipt_warehouse_id")
    private UUID receiptWarehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_warehouse_id", insertable = false, updatable = false)
    private Warehouse receiptWarehouse;
    @Column(name = "receipt_storage_location_id")
    private UUID receiptStorageLocationId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_storage_location_id", insertable = false, updatable = false)
    private StorageLocation receiptStorageLocation;
    @Column(name = "quality_status", length = 50)
    private String qualityStatus;
    @Column(name = "accreditation_warning", columnDefinition = "text")
    private String accreditationWarning;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        materialId = material == null ? materialId : material.getId();
        serviceRequestId = serviceRequest == null ? serviceRequestId : serviceRequest.getId();
        serviceOrderId = serviceOrder == null ? serviceOrderId : serviceOrder.getId();
        serviceActId = serviceAct == null ? serviceActId : serviceAct.getId();
        budgetItemId = budgetItem == null ? budgetItemId : budgetItem.getId();
        receiptWarehouseId = receiptWarehouse == null ? receiptWarehouseId : receiptWarehouse.getId();
        receiptStorageLocationId = receiptStorageLocation == null ? receiptStorageLocationId : receiptStorageLocation.getId();
    }
}
