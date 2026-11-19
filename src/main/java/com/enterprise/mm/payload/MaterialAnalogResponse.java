package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialAnalogResponse extends BaseRecordDto {
    private UUID materialId;
    private UUID analogMaterialId;
    private String analogType;
    private String interchangeability;
    private BigDecimal conversionFactor;
    private Integer priority;
    private String approvedBy;
    private LocalDate validFrom;
    private LocalDate validTo;
}