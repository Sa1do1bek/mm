package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkCompletionActCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID purchaseOrderId;
    private UUID purchaseOrderItemId;
    private UUID contractId;
    @Size(max = 240)
    private String workLocation;
    @Size(max = 120)
    private String milestone;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal completedQuantity;
    private BigDecimal amount;
    private String inspectionResult;
    private LocalDateTime acceptedAt;
    @Size(max = 120)
    private String acceptedBy;
    private String commissionNotes;
    private String remarks;
    private LocalDateTime documentReviewedAt;
    private LocalDateTime financeReviewedAt;
    private LocalDateTime sentToPaymentAt;
    private LocalDateTime paidAt;
    private LocalDateTime contractConsumedAt;
    @Size(max = 120)
    private String paymentReference;
}
