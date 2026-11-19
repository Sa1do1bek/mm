package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialReturnCondition;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.ReturnStockStatus;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialReturnLineResponse {
    private UUID id;
    private UUID issueRequestLineId;
    private UUID materialId;
    private String materialName;
    private BatchTrackingPolicy batchTrackingPolicy;
    private SerialTrackingPolicy serialTrackingPolicy;
    private String unitOfMeasure;
    private BigDecimal issuedQuantity;
    private BigDecimal returnableQuantity;
    private BigDecimal previouslyReturnedQuantity;
    private BigDecimal returnQuantity;
    private BigDecimal acceptedQuantity;
    private BigDecimal rejectedQuantity;
    private MaterialReturnCondition condition;
    private ReturnStockStatus targetStockStatus;
    private BigDecimal originalUnitCost;
    private BigDecimal totalReturnValue;
    private String serialNumber;
    private String ownerId;
    private String comment;
    private String inspectionNote;
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String getUnitOfMeasureShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unitOfMeasure); }
}
