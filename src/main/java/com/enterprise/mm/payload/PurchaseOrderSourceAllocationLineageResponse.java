package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseOrderSourceAllocationLineageResponse {
    private UUID allocationId;
    private UUID purchaseRequestItemId;
    private String purchaseRequestItemCode;
    private String purchaseRequestItemName;
    private UUID rfqPurchaseRequestSourceId;
    private BigDecimal orderedQuantity;
    private UUID demandPlanItemId;
    private String demandPlanItemCode;
    private String demandPlanItemName;
    private UUID demandPlanId;
    private String demandPlanCode;
    private String demandPlanName;
}
