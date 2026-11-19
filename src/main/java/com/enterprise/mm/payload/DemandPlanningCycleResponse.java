package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DemandPlanningCycleResponse extends BaseRecordDto {
    private Integer planYear;
    private String periodType;
    private Integer quarter;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate collectionStartDate;
    private LocalDate collectionEndDate;
    private LocalDate budgetReviewDeadline;
    private LocalDate consolidationDeadline;
    private LocalDate activationDate;
    private UUID ownerEmployeeId;
    private String ownerEmployeeName;
    private String ownerUsername;
    private String currencyCode;
    private Integer totalPlanCount;
    private Integer totalItemCount;
    private BigDecimal totalPlannedAmount;
    private BigDecimal totalApprovedAmount;
    private BigDecimal totalReservedQuantity;
    private LocalDateTime generatedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime activatedAt;
    private LocalDateTime closedAt;
}
