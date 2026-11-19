package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "demand_planning_cycles", uniqueConstraints = @UniqueConstraint(name = "uk_demand_planning_cycle_code", columnNames = {"tenant_id", "company_id", "code"}))
public class DemandPlanningCycle extends BusinessEntity {
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

    @Column(name = "collection_start_date")
    private LocalDate collectionStartDate;

    @Column(name = "collection_end_date")
    private LocalDate collectionEndDate;

    @Column(name = "budget_review_deadline")
    private LocalDate budgetReviewDeadline;

    @Column(name = "consolidation_deadline")
    private LocalDate consolidationDeadline;

    @Column(name = "activation_date")
    private LocalDate activationDate;

    @Column(name = "owner_username", length = 120)
    private String ownerUsername;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_employee_id")
    private Employee ownerEmployee;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @Column(name = "total_plan_count", nullable = false)
    private Integer totalPlanCount = 0;

    @Column(name = "total_item_count", nullable = false)
    private Integer totalItemCount = 0;

    @Column(name = "total_planned_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalPlannedAmount = BigDecimal.ZERO;

    @Column(name = "total_approved_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalApprovedAmount = BigDecimal.ZERO;

    @Column(name = "total_reserved_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal totalReservedQuantity = BigDecimal.ZERO;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
