package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockBalanceCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID materialId;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID storageBinId;
    private UUID stockBatchId;
    private BigDecimal quantity;
    private BigDecimal availableQuantity;
    private BigDecimal reservedQuantity;
    private BigDecimal quarantineQuantity;
    private BigDecimal totalValue;
    private LocalDateTime balanceAt;
}
