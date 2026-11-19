package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MaterialType;
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
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "demand_plan_items")
public class DemandPlanItem extends BusinessEntity {
    @Column(name = "demand_plan_id", nullable = false)
    private UUID demandPlanId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demand_plan_id", insertable = false, updatable = false)
    private DemandPlan demandPlan;

    @Column(name = "department_id")
    private UUID departmentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    private Department department;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;
    @Enumerated(EnumType.STRING)
    @Column(name = "material_type_snapshot", length = 30)
    private MaterialType materialTypeSnapshot;

    @Column(name = "budget_item_id")
    private UUID budgetItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_item_id", insertable = false, updatable = false)
    private BudgetItem budgetItem;

    @Column(name = "required_date")
    private LocalDate requiredDate;

    // Explicit operational deadline; requiredDate remains the monthly planning projection.
    @Column(name = "need_by_date")
    private LocalDate needByDate;

    public LocalDate fulfillmentDate() {
        return needByDate != null ? needByDate : requiredDate;
    }

    @Column(name = "demand_type", nullable = false, length = 30)
    private String demandType;

    @Column(nullable = false, length = 30)
    private String priority;

    @Column(name = "project_code", length = 80)
    private String projectCode;

    @Column(name = "repair_order_code", length = 80)
    private String repairOrderCode;

    @Column(name = "requested_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal requestedQuantity = BigDecimal.ZERO;

    @Column(name = "approved_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal approvedQuantity = BigDecimal.ZERO;

    // Approved existing-stock portion. approvedQuantity remains the procurement obligation.
    @Column(name = "stock_fulfillment_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal stockFulfillmentQuantity = BigDecimal.ZERO;

    @Column(name = "reserved_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal reservedQuantity = BigDecimal.ZERO;

    @Column(name = "estimated_unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal estimatedUnitPrice = BigDecimal.ZERO;

    @Column(name = "estimated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal estimatedAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @Column(name = "consolidated_demand_id")
    private UUID consolidatedDemandId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consolidated_demand_id", insertable = false, updatable = false)
    private ConsolidatedDemand consolidatedDemand;

    @Column(name = "purchase_request_item_id")
    private UUID purchaseRequestItemId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_item_id", insertable = false, updatable = false)
    private PurchaseRequestItem purchaseRequestItem;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        demandPlanId = demandPlan == null ? demandPlanId : demandPlan.getId();
        departmentId = department == null ? departmentId : department.getId();
        materialId = material == null ? materialId : material.getId();
        budgetItemId = budgetItem == null ? budgetItemId : budgetItem.getId();
        consolidatedDemandId = consolidatedDemand == null ? consolidatedDemandId : consolidatedDemand.getId();
        purchaseRequestItemId = purchaseRequestItem == null ? purchaseRequestItemId : purchaseRequestItem.getId();
    }
}
