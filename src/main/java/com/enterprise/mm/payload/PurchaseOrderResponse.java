package com.enterprise.mm.payload;

import com.enterprise.mm.payload.BusinessActionDtos.ReceiptOutcome;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseOrderResponse extends BaseRecordDto {
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID purchaseRequestId;
    private String purchaseRequestCode;
    private String purchaseRequestName;
    private UUID demandPlanId;
    private String demandPlanCode;
    private String demandPlanName;
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private LocalDate orderDate;
    private LocalDate expectedDeliveryDate;
    private String requestType;
    private BigDecimal totalAmount;
    private String currencyCode;
    private BigDecimal exchangeRate;
    private Boolean sendReady;
    private String sendBlockedReason;
    private String fulfillmentState;
    private String invoicingState;
    private String paymentState;
    private String administrativeCloseState;
    private Boolean closeReady;
    private String closeBlockedReason;
    private ReceiptOutcome receiptOutcome;

    /** Supplier PO collaboration summary (Phase A). Null/NOT_SENT/true for any PO that
     * predates this feature or was never dispatched through it. */
    private UUID dispatchId;
    private String dispatchStatus;
    private String collaborationStatus;
    private Boolean legacy;
}
