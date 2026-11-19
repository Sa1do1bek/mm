package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceAcceptanceActResponse extends BaseRecordDto {
    private UUID serviceRequestId;
    private String serviceRequestCode;
    private String serviceRequestName;
    private UUID serviceOrderId;
    private String serviceOrderCode;
    private String serviceOrderName;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private LocalDateTime acceptedAt;
    private String acceptedBy;
    private BigDecimal amount;
    private BigDecimal vatAmount;
    private BigDecimal vatRate;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String stage;
    private LocalDateTime documentReviewedAt;
    private LocalDateTime financeReviewedAt;
    private LocalDateTime sentToPaymentAt;
    private LocalDateTime paidAt;
    private LocalDateTime budgetConsumedAt;
    private LocalDateTime contractConsumedAt;
    private String paymentReference;
    private String commissionNotes;
    private String remarks;
}
