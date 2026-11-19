package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialMarketPriceCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    private UUID materialId;
    private UUID warehouseId;
    private BigDecimal marketUnitPrice;
    private BigDecimal netRealizableUnitPrice;
    private String currencyCode;
    private LocalDate effectiveDate;
    @Size(max = 80)
    private String sourceType;
    @Size(max = 160)
    private String sourceReference;
}
