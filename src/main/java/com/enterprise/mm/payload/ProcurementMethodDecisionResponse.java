package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcurementMethodDecisionResponse extends BaseRecordDto {
    private String decisionNumber;
    private UUID purchaseRequestId;
    private String selectedMethod;
    private String justification;
    private BigDecimal estimatedAmount;
    private String currencyCode;
    private Integer potentialSupplierCount;
    private UUID commissionId;
    private UUID marketResearchAttachmentId;
    private UUID technicalSpecificationAttachmentId;
    private String exceptionReason;
    private LocalDateTime submittedAt;
    private LocalDateTime approvedAt;
    private String approvedBy;
    private LocalDateTime rejectedAt;
    private String rejectedBy;
    private String rejectionReason;
}
