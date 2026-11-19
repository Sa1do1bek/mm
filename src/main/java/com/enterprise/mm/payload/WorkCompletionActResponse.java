package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkCompletionActResponse extends BaseRecordDto {
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID purchaseOrderItemId;
    private String purchaseOrderItemCode;
    private String purchaseOrderItemName;
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private String workLocation;
    private String milestone;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal completedQuantity;
    private BigDecimal amount;
    private String inspectionResult;
    private LocalDateTime acceptedAt;
    private String acceptedBy;
    private String commissionNotes;
    private String remarks;
    private LocalDateTime documentReviewedAt;
    private LocalDateTime financeReviewedAt;
    private LocalDateTime sentToPaymentAt;
    private LocalDateTime paidAt;
    private LocalDateTime contractConsumedAt;
    private String paymentReference;
}
