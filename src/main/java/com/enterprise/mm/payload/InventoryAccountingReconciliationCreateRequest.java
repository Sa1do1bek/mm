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
public class InventoryAccountingReconciliationCreateRequest {
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
