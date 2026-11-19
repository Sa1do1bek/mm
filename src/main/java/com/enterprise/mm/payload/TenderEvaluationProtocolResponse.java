package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderEvaluationProtocolResponse extends BaseRecordDto {
    private UUID rfqId;
    private String rfqCode;
    private String rfqName;
    private String protocolNumber;
    private LocalDateTime evaluatedAt;
    private String selectionRule;
    private UUID recommendedQuotationId;
    private String recommendedQuotationCode;
    private String recommendedQuotationName;
    private UUID recommendedSupplierId;
    private String recommendedSupplierCode;
    private String recommendedSupplierName;
    private String recommendationReason;
    private String scoringSummary;
    private String conflictSummary;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime postedAt;
}