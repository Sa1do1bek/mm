package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import com.enterprise.mm.enums.InventoryDiscrepancyType;
import com.enterprise.mm.enums.InventoryLineStatus;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryItemCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID inventoryDocumentId;
    private UUID materialId;
    private BigDecimal systemQuantity;
    private BigDecimal countedQuantity;
    private BigDecimal differenceQuantity;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID storageBinId;
    private UUID stockBatchId;
    private BigDecimal unitCost;
    private BigDecimal differenceAmount;
    private InventoryLineStatus lineStatus;
    private InventoryDiscrepancyType discrepancyType;
    private String discrepancyReason;
    private String commentText;
}