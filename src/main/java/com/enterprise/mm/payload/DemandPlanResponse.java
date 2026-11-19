package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DemandPlanResponse extends BaseRecordDto {
    private UUID planningCycleId;
    private String demandPlanName;
    private String requestType;
    private Integer planYear;
    private String periodType;
    private Integer quarter;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private UUID departmentId;
    private String departmentName;
    private UUID budgetItemId;
    private String budgetItemName;
    private String ownerUsername;
    private String currencyCode;
    private BigDecimal totalPlannedAmount;
    private BigDecimal totalApprovedAmount;
    private BigDecimal totalReservedQuantity;
    private List<DemandPlanItemResponse> items;
    private LocalDateTime consolidatedAt;
    private LocalDateTime approvedAt;
}
