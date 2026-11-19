package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcurementProcedureResponse extends BaseRecordDto {
    private String procedureNumber;
    private UUID methodDecisionId;
    private UUID procurementPlanId;
    private UUID purchaseRequestId;
    private String method;
    private String executionPath;
    private UUID campaignId;
    private UUID rfqId;
    private UUID directAwardId;
    private UUID awardedSupplierId;
    private UUID purchaseOrderId;
    private UUID contractId;
    private String awardReference;
    private UUID priceDiscoveryRfqId;
    private boolean requiresPriceDiscovery;
    private BigDecimal finalizedAmount;
    private String finalizedCurrencyCode;
    private UUID priceBasisCalculationId;
    private LocalDateTime launchedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private String cancellationReason;
}
