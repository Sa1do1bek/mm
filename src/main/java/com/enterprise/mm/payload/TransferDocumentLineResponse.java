package com.enterprise.mm.payload;

import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransferDocumentLineResponse {
    private UUID id;
    private UUID materialId;
    private String materialName;
    private BatchTrackingPolicy batchTrackingPolicy;
    private SerialTrackingPolicy serialTrackingPolicy;
    private String unitOfMeasure;
    private BigDecimal returnedQuantity;
    private BigDecimal postedLossQuantity;
    private BigDecimal plannedQuantity;
    private BigDecimal cancelledQuantity;
    private BigDecimal dispatchedQuantity;
    private BigDecimal inTransitQuantity;
    private BigDecimal receivedQuantity;
    private BigDecimal damagedQuantity;
    private BigDecimal missingQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal unitCost;
    private BigDecimal inTransitValue;
    private UUID sourceStockBatchId;
    private UUID destinationStockBatchId;
    private UUID sourceStorageLocationId;
    private UUID destinationStorageLocationId;
    private String shortageReason;
    private String discrepancyReason;
    private String discrepancyEvidence;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String getUnitOfMeasureShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unitOfMeasure); }
}
