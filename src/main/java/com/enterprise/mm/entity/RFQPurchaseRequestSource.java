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
@Table(name = "rfq_purchase_request_sources")
public class RFQPurchaseRequestSource extends BusinessEntity {
    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", insertable = false, updatable = false)
    private RFQ rfq;
    @Column(name = "rfq_item_id", nullable = false)
    private UUID rfqItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_item_id", insertable = false, updatable = false)
    private RFQItem rfqItem;
    @Column(name = "purchase_request_id", nullable = false)
    private UUID purchaseRequestId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_id", insertable = false, updatable = false)
    private PurchaseRequest purchaseRequest;
    @Column(name = "purchase_request_item_id", nullable = false)
    private UUID purchaseRequestItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_item_id", insertable = false, updatable = false)
    private PurchaseRequestItem purchaseRequestItem;
    @Column(name = "allocated_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal allocatedQuantity = BigDecimal.ZERO;
    @Column(name = "source_required_date")
    private LocalDate sourceRequiredDate;
    @Column(name = "source_department_id")
    private UUID sourceDepartmentId;
    @Column(name = "source_budget_item_id")
    private UUID sourceBudgetItemId;
    @Column(name = "source_currency_code", length = 3)
    private String sourceCurrencyCode;
    @Column(name = "source_unit", length = 80)
    private String sourceUnit;
    @Column(name = "allocated_at", nullable = false)
    private LocalDateTime allocatedAt;
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        rfqId = rfq == null ? rfqId : rfq.getId();
        rfqItemId = rfqItem == null ? rfqItemId : rfqItem.getId();
        purchaseRequestId = purchaseRequest == null ? purchaseRequestId : purchaseRequest.getId();
        purchaseRequestItemId = purchaseRequestItem == null ? purchaseRequestItemId : purchaseRequestItem.getId();
    }
}
