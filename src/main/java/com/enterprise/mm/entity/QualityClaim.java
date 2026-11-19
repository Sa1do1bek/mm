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
@Table(name = "quality_claims")
public class QualityClaim extends BusinessEntity {
    @Column(name = "claim_number", length = 120)
    private String claimNumber;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "quality_nonconformance_id")
    private UUID qualityNonconformanceId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_nonconformance_id", insertable = false, updatable = false)
    private QualityNonconformance qualityNonconformance;

    @Column(name = "quality_inspection_id")
    private UUID qualityInspectionId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quality_inspection_id", insertable = false, updatable = false)
    private QualityInspection qualityInspection;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "material_id")
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(name = "claim_date")
    private LocalDate claimDate;

    @Column(name = "claim_amount", precision = 19, scale = 4)
    private BigDecimal claimAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @Column(name = "requested_action", length = 1000)
    private String requestedAction;

    @Column(name = "supplier_response", columnDefinition = "text")
    private String supplierResponse;

    @Column(name = "response_due_date")
    private LocalDate responseDueDate;

    @Column(name = "corrective_action_id")
    private UUID correctiveActionId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corrective_action_id", insertable = false, updatable = false)
    private SupplierCorrectiveAction correctiveAction;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
        qualityNonconformanceId = qualityNonconformance == null ? qualityNonconformanceId : qualityNonconformance.getId();
        qualityInspectionId = qualityInspection == null ? qualityInspectionId : qualityInspection.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        materialId = material == null ? materialId : material.getId();
        correctiveActionId = correctiveAction == null ? correctiveActionId : correctiveAction.getId();
    }
}
