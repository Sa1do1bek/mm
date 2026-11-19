package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LandedCostAllocationLineCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
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
