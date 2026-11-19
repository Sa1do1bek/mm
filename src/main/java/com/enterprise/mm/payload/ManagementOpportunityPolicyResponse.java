package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ManagementOpportunityType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManagementOpportunityPolicyResponse extends BaseRecordDto {
    private ManagementOpportunityType opportunityType;
    private boolean active;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private UUID materialGroupId;
    private String materialGroupCode;
    private String materialGroupName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private BigDecimal annualCarryingCostRate;
    private BigDecimal dailyImpactRate;
    private BigDecimal attributableShare;
    private String currencyCode;
    private LocalDateTime activatedAt;
    private String activatedBy;
}
