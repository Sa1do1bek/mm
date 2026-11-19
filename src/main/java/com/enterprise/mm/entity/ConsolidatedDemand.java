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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "consolidated_demands", uniqueConstraints = @UniqueConstraint(name = "uk_consolidated_demand_key", columnNames = {"tenant_id", "company_id", "demand_plan_id", "material_id", "budget_item_id", "period_start", "period_end", "highest_priority"}))
public class ConsolidatedDemand extends BusinessEntity {
    @Column(name = "demand_plan_id", nullable = false)
    private UUID demandPlanId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demand_plan_id", insertable = false, updatable = false)
    private DemandPlan demandPlan;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;

    @Column(name = "budget_item_id")
    private UUID budgetItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_item_id", insertable = false, updatable = false)
    private BudgetItem budgetItem;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "highest_priority", nullable = false, length = 30)
    private String highestPriority;

    @Column(name = "total_requested_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal totalRequestedQuantity = BigDecimal.ZERO;

    @Column(name = "total_approved_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal totalApprovedQuantity = BigDecimal.ZERO;

    @Column(name = "total_estimated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalEstimatedAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @Column(name = "source_item_count", nullable = false)
    private Integer sourceItemCount = 0;

    @Column(name = "purchase_request_id")
    private UUID purchaseRequestId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_id", insertable = false, updatable = false)
    private PurchaseRequest purchaseRequest;

    @Column(name = "consolidated_at")
    private LocalDateTime consolidatedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        demandPlanId = demandPlan == null ? demandPlanId : demandPlan.getId();
        materialId = material == null ? materialId : material.getId();
        budgetItemId = budgetItem == null ? budgetItemId : budgetItem.getId();
        purchaseRequestId = purchaseRequest == null ? purchaseRequestId : purchaseRequest.getId();
    }
}
