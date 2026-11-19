package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcurementCampaignResponse extends BaseRecordDto {
    private String campaignNumber;
    private UUID materialGroupId;
    private String materialGroupName;
    private UUID rfqId;
    private String rfqName;
    private UUID priceDiscoveryRfqId;
    private UUID priceBasisCalculationId;
    private UUID purchaseRequestId;
    private String purchaseRequestName;
    private UUID methodDecisionId;
    private UUID procurementProcedureId;
    private String campaignType;
    private String procedureType;
    private Integer targetSupplierCount;
    private BigDecimal plannedSpendAmount;
    private BigDecimal approvedSpendAmount;
    private BigDecimal savingsTargetAmount;
    private String currencyCode;
    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
    private LocalDateTime launchedAt;
    private LocalDateTime completedAt;
}
