package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcurementCampaignCreateRequest {
    private String name;
    private String description;
    private UUID procurementProcedureId;
    private String campaignType;
    private Integer targetSupplierCount;
    private BigDecimal plannedSpendAmount;
    private BigDecimal approvedSpendAmount;
    private BigDecimal savingsTargetAmount;
    private String currencyCode;
    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
}
