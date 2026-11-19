package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeliveryScheduleResponse extends BaseRecordDto {
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private LocalDate scheduleDate;
    private BigDecimal plannedQuantity;
    private BigDecimal plannedAmount;
    private BigDecimal receivedQuantity;
    private BigDecimal receivedAmount;
    private boolean overdueFlag;
    private LocalDateTime controlledAt;
    private String businessImpactReference;
    private String businessImpactComment;
    private BigDecimal attributableShare;
    private String impactCurrencyCode;
}
