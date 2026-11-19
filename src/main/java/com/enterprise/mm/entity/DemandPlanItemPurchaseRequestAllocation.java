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
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "demand_plan_item_pr_allocations",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_demand_item_pr_allocation",
        columnNames = {"tenant_id", "company_id", "demand_plan_item_id", "purchase_request_item_id"}
    )
)
public class DemandPlanItemPurchaseRequestAllocation extends BusinessEntity {
    @Column(name = "demand_plan_item_id", nullable = false)
    private UUID demandPlanItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demand_plan_item_id", insertable = false, updatable = false)
    private DemandPlanItem demandPlanItem;

    @Column(name = "purchase_request_item_id", nullable = false)
    private UUID purchaseRequestItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_item_id", insertable = false, updatable = false)
    private PurchaseRequestItem purchaseRequestItem;

    @Column(name = "allocated_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal allocatedQuantity = BigDecimal.ZERO;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        demandPlanItemId = demandPlanItem == null ? demandPlanItemId : demandPlanItem.getId();
        purchaseRequestItemId = purchaseRequestItem == null ? purchaseRequestItemId : purchaseRequestItem.getId();
    }
}
