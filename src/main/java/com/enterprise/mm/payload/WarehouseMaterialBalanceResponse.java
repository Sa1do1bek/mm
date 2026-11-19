package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WarehouseMaterialBalanceResponse {
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private String materialStatus;
    private UUID materialGroupId;
    private UUID unitOfMeasureId;
    private UUID warehouseId;
    private BigDecimal quantity;
    private BigDecimal availableQuantity;
    private BigDecimal reservedQuantity;
    private BigDecimal quarantineQuantity;
    private BigDecimal totalValue;
    private LocalDateTime balanceAt;
}
