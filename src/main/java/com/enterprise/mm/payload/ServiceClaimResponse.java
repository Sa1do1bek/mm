package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceClaimResponse extends BaseRecordDto {
    private String originType;
    private UUID serviceRequestId;
    private String serviceRequestCode;
    private String serviceRequestName;
    private UUID serviceActId;
    private String serviceActCode;
    private String serviceActName;
    private UUID serviceOrderId;
    private String serviceOrderCode;
    private String serviceOrderName;
    private UUID purchaseOrderItemId;
    private String purchaseOrderItemCode;
    private String purchaseOrderItemName;
    private UUID workCompletionActId;
    private String workCompletionActCode;
    private String workCompletionActName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String claimType;
    private String severity;
    private UUID assignedTo;
    private LocalDate deadline;
    private BigDecimal penaltyAmount;
    private String currencyCode;
    private String supplierResponse;
    private LocalDateTime sentAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
}
