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
@Table(name = "procurement_category_strategies")
public class ProcurementCategoryStrategy extends BusinessEntity {
    @Column(name = "material_group_id", nullable = false)
    private UUID materialGroupId;

    @Column(name = "strategy_year", nullable = false)
    private Integer strategyYear;

    @Column(name = "owner_employee_id")
    private UUID ownerEmployeeId;

    @Column(name = "budget_item_id")
    private UUID budgetItemId;

    @Column(name = "strategy_type", nullable = false, length = 60)
    private String strategyType = "SUPPLY_SECURITY";

    @Column(name = "sourcing_approach", nullable = false, length = 60)
    private String sourcingApproach = "COMPETITIVE_TENDER";

    @Column(name = "supplier_market", columnDefinition = "text")
    private String supplierMarket;

    @Column(name = "risk_level", nullable = false, length = 40)
    private String riskLevel = "MEDIUM";

    @Column(name = "target_savings_percent", nullable = false, precision = 9, scale = 4)
    private BigDecimal targetSavingsPercent = BigDecimal.ZERO;

    @Column(name = "planned_spend_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal plannedSpendAmount = BigDecimal.ZERO;

    @Column(name = "approved_spend_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal approvedSpendLimit = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}