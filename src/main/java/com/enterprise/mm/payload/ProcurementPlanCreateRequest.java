package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcurementPlanCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
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
