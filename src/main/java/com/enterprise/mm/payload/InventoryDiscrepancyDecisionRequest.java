package com.enterprise.mm.payload;

import com.enterprise.mm.enums.InventoryDiscrepancyDecisionType;
import com.enterprise.mm.enums.InventoryDiscrepancyType;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryDiscrepancyDecisionRequest {
    private Long expectedLineVersion;
    private Long countRevision;
    private java.util.List<UUID> evidenceIds;
    private UUID sourceCorrectionMovementId;
    private InventoryDiscrepancyType discrepancyType;
    private InventoryDiscrepancyDecisionType decisionType;
    private UUID responsibleUserId;
    private UUID linkedStockMovementId;
    private UUID linkedPurchaseOrderId;
    private UUID linkedSupplierId;
    private UUID linkedContractId;
    private BigDecimal compensationAmount;
    private String decisionComment;
    private String reason;
    private String comment;
}
