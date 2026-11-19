package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcurementPlanResponse extends BaseRecordDto {
    private String planNumber;
    private Integer planYear;
    private String periodType;
    private Integer periodQuarter;
    private UUID demandPlanningCycleId;
    private UUID categoryStrategyId;
    private UUID materialGroupId;
    private UUID budgetItemId;
    private UUID ownerEmployeeId;
    private BigDecimal plannedSpendAmount;
    private BigDecimal approvedBudgetAmount;
    private BigDecimal reservedBudgetAmount;
    private BigDecimal expectedSavingsAmount;
    private String currencyCode;
    private LocalDate planStartDate;
    private LocalDate planEndDate;
    private Integer demandCount;
    private Integer campaignCount;
    private LocalDateTime submittedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime activatedAt;
    private LocalDateTime closedAt;
}