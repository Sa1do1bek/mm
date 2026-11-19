package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LandedCostAllocationLineResponse extends BaseRecordDto {
    private UUID allocationDocumentId;
    private UUID materialId;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID stockBatchId;
    private BigDecimal allocationBase;
    private BigDecimal quantity;
    private BigDecimal allocatedAmount;
    private BigDecimal allocatedBaseAmount;
    private BigDecimal unitCostIncrease;
}