package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConsolidatedDemandResponse extends BaseRecordDto {
    private UUID demandPlanId;
    private String demandPlanCode;
    private String demandPlanName;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private UUID budgetItemId;
    private String budgetItemCode;
    private String budgetItemName;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String highestPriority;
    private BigDecimal totalRequestedQuantity;
    private BigDecimal totalApprovedQuantity;
    private BigDecimal totalEstimatedAmount;
    private String currencyCode;
    private Integer sourceItemCount;
    private UUID purchaseRequestId;
    private String purchaseRequestCode;
    private String purchaseRequestName;
    private LocalDateTime consolidatedAt;
}