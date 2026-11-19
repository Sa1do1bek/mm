package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryAccountingReconciliationResponse extends BaseRecordDto {
    private BigDecimal transitStockValue;
    private BigDecimal physicalStockValue;

    private LocalDate periodStart;
    private LocalDate periodEnd;
    private UUID warehouseId;
    private String accountingSystem;
    private String externalReference;
    private BigDecimal inventoryValue;
    private BigDecimal accountingValue;
    private BigDecimal differenceAmount;
    private BigDecimal toleranceAmount;
    private String differenceStatus;
    private LocalDateTime reconciledAt;
    private LocalDateTime postedAt;
}