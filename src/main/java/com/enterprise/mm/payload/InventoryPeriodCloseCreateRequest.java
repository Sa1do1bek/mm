package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryPeriodCloseCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private UUID warehouseId;
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
