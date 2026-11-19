package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ManagementOpportunityConfidence;
import com.enterprise.mm.enums.ManagementOpportunityDataCompleteness;
import com.enterprise.mm.enums.ManagementOpportunityType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManagementOpportunityResponse extends BaseRecordDto {
    private ManagementOpportunityType opportunityType;
    private UUID calculationRunId;
    private UUID policyId;
    private String sourceAggregateType;
    private UUID sourceAggregateId;
    private UUID sourceLineId;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private LocalDate asOfDate;
    private String sourceCurrencyCode;
    private String normalizedCurrencyCode;
    private BigDecimal sourceAmount;
    private BigDecimal normalizedAmount;
    private BigDecimal baselineAmount;
    private BigDecimal actualAmount;
    private BigDecimal quantity;
    private BigDecimal exchangeRate;
    private UUID exchangeRateId;
    private LocalDate exchangeRateDate;
    private String exchangeRateSource;
    private String normalizationQuality;
    private String formulaCode;
    private String formulaSnapshot;
    private ManagementOpportunityFormulaResponse formula;
    private ManagementOpportunityConfidence confidence;
    private ManagementOpportunityDataCompleteness dataCompleteness;
    private String assignedRole;
    private LocalDateTime submittedAt;
    private String submittedBy;
    private LocalDateTime reviewedAt;
    private String reviewedBy;
    private String reviewComment;
    private LocalDateTime approvedAt;
    private String approvedBy;
    private String rejectionReason;
    private List<ManagementOpportunityEvidenceResponse> evidence;
}
