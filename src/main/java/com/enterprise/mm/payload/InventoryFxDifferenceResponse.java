package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryFxDifferenceResponse extends BaseRecordDto {
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate documentDate;
    private String referenceType;
    private UUID referenceId;
    private UUID materialId;
    private UUID warehouseId;
    private UUID supplierId;
    private String documentCurrencyCode;
    private String baseCurrencyCode;
    private BigDecimal foreignAmount;
    private BigDecimal documentRate;
    private BigDecimal closingRate;
    private BigDecimal baseAmount;
    private BigDecimal revaluedBaseAmount;
    private BigDecimal differenceAmount;
    private String differenceType;
    private LocalDateTime postedAt;
}