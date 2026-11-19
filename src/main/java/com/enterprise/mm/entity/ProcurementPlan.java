package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "procurement_plans")
public class ProcurementPlan extends BusinessEntity {
    @Column(name = "plan_number", length = 120)
    private String planNumber;

    @Column(name = "plan_year", nullable = false)
    private Integer planYear;

    @Column(name = "period_type", nullable = false, length = 20)
    private String periodType = "YEAR";

    @Column(name = "period_quarter")
    private Integer periodQuarter;

    @Column(name = "demand_planning_cycle_id")
    private UUID demandPlanningCycleId;

    @Column(name = "category_strategy_id")
    private UUID categoryStrategyId;

    @Column(name = "material_group_id")
    private UUID materialGroupId;

    @Column(name = "budget_item_id")
    private UUID budgetItemId;

    @Column(name = "owner_employee_id")
    private UUID ownerEmployeeId;

    @Column(name = "planned_spend_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal plannedSpendAmount = BigDecimal.ZERO;

    @Column(name = "approved_budget_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal approvedBudgetAmount = BigDecimal.ZERO;

    @Column(name = "reserved_budget_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedBudgetAmount = BigDecimal.ZERO;

    @Column(name = "expected_savings_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedSavingsAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "plan_start_date")
    private LocalDate planStartDate;

    @Column(name = "plan_end_date")
    private LocalDate planEndDate;

    @Column(name = "demand_count", nullable = false)
    private Integer demandCount = 0;

    @Column(name = "campaign_count", nullable = false)
    private Integer campaignCount = 0;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}