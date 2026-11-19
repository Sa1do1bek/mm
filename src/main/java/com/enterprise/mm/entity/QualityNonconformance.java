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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "quality_nonconformances")
public class QualityNonconformance extends BusinessEntity {
    @Column(name = "quality_inspection_id", nullable = false)
    private UUID qualityInspectionId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_inspection_id", insertable = false, updatable = false)
    private QualityInspection qualityInspection;

    @Column(name = "supplier_id")
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(name = "stock_movement_id")
    private UUID stockMovementId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_movement_id", insertable = false, updatable = false)
    private StockMovement stockMovement;

    @Column(name = "severity", nullable = false, length = 40)
    private String severity = "MEDIUM";

    @Column(name = "nonconformance_type", length = 120)
    private String nonconformanceType;

    @Column(name = "detected_quantity", precision = 22, scale = 7)
    private BigDecimal detectedQuantity = BigDecimal.ZERO;

    @Column(name = "defect_description", columnDefinition = "text")
    private String defectDescription;

    @Column(name = "root_cause", columnDefinition = "text")
    private String rootCause;

    @Column(name = "disposition", length = 80)
    private String disposition = "QUARANTINE";

    @Column(name = "capa_required", nullable = false)
    private boolean capaRequired;

    @Column(name = "claim_id")
    private UUID claimId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", insertable = false, updatable = false)
    private QualityClaim claim;

    @Column(name = "corrective_action_id")
    private UUID correctiveActionId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corrective_action_id", insertable = false, updatable = false)
    private SupplierCorrectiveAction correctiveAction;

    @Column(name = "detected_at")
    private LocalDateTime detectedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        qualityInspectionId = qualityInspection == null ? qualityInspectionId : qualityInspection.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        materialId = material == null ? materialId : material.getId();
        stockMovementId = stockMovement == null ? stockMovementId : stockMovement.getId();
        claimId = claim == null ? claimId : claim.getId();
        correctiveActionId = correctiveAction == null ? correctiveActionId : correctiveAction.getId();
    }
}
