package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderEvaluationProtocolCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID rfqId;
    private String protocolNumber;
    private LocalDateTime evaluatedAt;
    private String selectionRule;
    private UUID recommendedQuotationId;
    private UUID recommendedSupplierId;
    private String recommendationReason;
    private String scoringSummary;
    private String conflictSummary;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime postedAt;
}
