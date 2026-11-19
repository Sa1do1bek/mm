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
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "demand_plan_item_months")
public class DemandPlanItemMonth extends BusinessEntity {

    @Column(name = "demand_plan_item_id", nullable = false)
    private UUID demandPlanItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demand_plan_item_id", insertable = false, updatable = false)
    private DemandPlanItem demandPlanItem;

    @Column(name = "demand_plan_id", nullable = false)
    private UUID demandPlanId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demand_plan_id", insertable = false, updatable = false)
    private DemandPlan demandPlan;

    @Column(name = "\"month\"", nullable = false)
    private Integer month;


    @Column(name = "qty", nullable = false, precision = 22, scale = 7)
    private BigDecimal qty = BigDecimal.ZERO;

    @Column(name = "approved_qty", nullable = false, precision = 22, scale = 7)
    private BigDecimal approvedQty = BigDecimal.ZERO;

    @Column(name = "reserved_qty", nullable = false, precision = 22, scale = 7)
    private BigDecimal reservedQty = BigDecimal.ZERO;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        demandPlanItemId = demandPlanItem == null ? demandPlanItemId : demandPlanItem.getId();
        demandPlanId = demandPlan == null ? demandPlanId : demandPlan.getId();
    }
}
