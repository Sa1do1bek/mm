package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcurementCategoryStrategyResponse extends BaseRecordDto {
    private UUID materialGroupId;
    private Integer strategyYear;
    private UUID ownerEmployeeId;
    private UUID budgetItemId;
    private String strategyType;
    private String sourcingApproach;
    private String supplierMarket;
    private String riskLevel;
    private BigDecimal targetSavingsPercent;
    private BigDecimal plannedSpendAmount;
    private BigDecimal approvedSpendLimit;
    private String currencyCode;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalDateTime approvedAt;
    private LocalDateTime activatedAt;
    private LocalDateTime closedAt;
}