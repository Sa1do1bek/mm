package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryPeriodCloseResponse extends BaseRecordDto {
    private BigDecimal transitStockValue;
    private BigDecimal physicalStockValue;

    private LocalDate periodStart;
    private LocalDate periodEnd;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private BigDecimal totalStockValue;
    private BigDecimal landedCostAmount;
    private BigDecimal fxDifferenceAmount;
    private long movementCount;
    private long openMovementCount;
    private long valuationCount;
    private UUID accountingReconciliationId;
    private String accountingReconciliationStatus;
    private BigDecimal accountingDifferenceAmount;
    private LocalDateTime closedAt;
}
