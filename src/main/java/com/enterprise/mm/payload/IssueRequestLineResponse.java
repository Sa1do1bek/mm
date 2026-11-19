package com.enterprise.mm.payload;

import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IssueRequestLineResponse {
    private UUID id;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private BatchTrackingPolicy batchTrackingPolicy;
    private SerialTrackingPolicy serialTrackingPolicy;
    private String unitOfMeasure;
    private BigDecimal requestedQuantity;
    private BigDecimal approvedQuantity;
    private BigDecimal issuedQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private String comment;
    private UUID reservationId;
    private UUID stockBatchId;
    private UUID stockMovementId;
    private UUID storageLocationId;
    private UUID sourceDemandPlanItemId;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String getUnitOfMeasureShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unitOfMeasure); }
}
