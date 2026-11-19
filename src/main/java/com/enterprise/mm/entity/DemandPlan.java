package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.RequestType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "demand_plans", uniqueConstraints = @UniqueConstraint(name = "uk_demand_plan_code", columnNames = {"tenant_id", "company_id", "code"}))
public class DemandPlan extends BusinessEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "planning_cycle_id")
    private DemandPlanningCycle planningCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20)
    private RequestType requestType = RequestType.GOODS;

    @Column(name = "plan_year", nullable = false)
    private Integer planYear;

    @Column(name = "period_type", nullable = false, length = 20)
    private String periodType;

    @Column(name = "quarter_no")
    private Integer quarter;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_item_id")
    private BudgetItem budgetItem;

    @Column(name = "owner_username", length = 120)
    private String ownerUsername;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @Column(name = "total_planned_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalPlannedAmount = BigDecimal.ZERO;

    @Column(name = "total_approved_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalApprovedAmount = BigDecimal.ZERO;

    @Column(name = "total_reserved_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal totalReservedQuantity = BigDecimal.ZERO;

    @Column(name = "consolidated_at")
    private LocalDateTime consolidatedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
}
