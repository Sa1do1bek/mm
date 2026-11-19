package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcurementMethodDecisionCreateRequest {
    @Size(max = 240)
    private String name;
    private String description;
    private UUID purchaseRequestId;
    @NotNull
    private String selectedMethod;
    private String justification;
    private BigDecimal estimatedAmount;
    @Size(max = 3)
    private String currencyCode;
    private Integer potentialSupplierCount;
    private UUID commissionId;
    private UUID marketResearchAttachmentId;
    private UUID technicalSpecificationAttachmentId;
    private String exceptionReason;
}
