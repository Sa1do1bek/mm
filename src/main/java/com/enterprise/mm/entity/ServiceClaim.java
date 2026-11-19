package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ServiceClaimOriginType;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "service_claims")
public class ServiceClaim extends BusinessEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "origin_type", nullable = false, length = 30)
    private ServiceClaimOriginType originType;

    @Column(name = "service_request_id")
    private UUID serviceRequestId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_request_id", insertable = false, updatable = false)
    private ServiceRequest serviceRequest;

    @Column(name = "service_act_id")
    private UUID serviceActId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_act_id", insertable = false, updatable = false)
    private ServiceAcceptanceAct serviceAct;

    @Column(name = "service_order_id")
    private UUID serviceOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_order_id", insertable = false, updatable = false)
    private ServiceOrder serviceOrder;

    @Column(name = "purchase_order_item_id")
    private UUID purchaseOrderItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_item_id", insertable = false, updatable = false)
    private PurchaseOrderItem purchaseOrderItem;

    @Column(name = "work_completion_act_id")
    private UUID workCompletionActId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_completion_act_id", insertable = false, updatable = false)
    private WorkCompletionAct workCompletionAct;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "claim_type", nullable = false, length = 60)
    private String claimType;

    @Column(nullable = false, length = 40)
    private String severity = "MEDIUM";

    @Column(name = "assigned_to")
    private UUID assignedTo;

    private LocalDate deadline;

    @Column(precision = 19, scale = 4)
    private BigDecimal penaltyAmount = BigDecimal.ZERO;

    @Column(length = 3)
    private String currencyCode = "UZS";

    @Column(columnDefinition = "text")
    private String supplierResponse;

    private LocalDateTime sentAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        serviceRequestId = serviceRequest == null ? serviceRequestId : serviceRequest.getId();
        serviceActId = serviceAct == null ? serviceActId : serviceAct.getId();
        serviceOrderId = serviceOrder == null ? serviceOrderId : serviceOrder.getId();
        purchaseOrderItemId = purchaseOrderItem == null ? purchaseOrderItemId : purchaseOrderItem.getId();
        workCompletionActId = workCompletionAct == null ? workCompletionActId : workCompletionAct.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
    }
}
