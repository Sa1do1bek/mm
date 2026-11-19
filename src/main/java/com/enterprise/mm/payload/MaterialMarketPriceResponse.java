package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialMarketPriceResponse extends BaseRecordDto {
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private UUID warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private BigDecimal marketUnitPrice;
    private BigDecimal netRealizableUnitPrice;
    private String currencyCode;
    private LocalDate effectiveDate;
    private String sourceType;
    private String sourceReference;
    private String approvedBy;
    private LocalDateTime approvedAt;
}
