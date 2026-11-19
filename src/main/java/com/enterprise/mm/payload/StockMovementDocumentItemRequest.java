package com.enterprise.mm.payload;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockMovementDocumentItemRequest {
    @NotNull
    private UUID materialId;
    private UUID storageLocationId;
    private UUID storageBinId;
    private UUID targetStorageLocationId;
    private UUID stockBatchId;
    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal quantity;
    private BigDecimal unitCost;
}
