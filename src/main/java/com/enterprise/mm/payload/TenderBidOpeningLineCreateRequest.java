package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderBidOpeningLineCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID protocolId;
    private UUID rfqId;
    private UUID quotationId;
    private UUID supplierId;
    private LocalDateTime submittedAt;
    private BigDecimal totalAmount;
    private String currencyCode;
    private boolean acceptedForEvaluation;
    private String rejectionReason;
}
