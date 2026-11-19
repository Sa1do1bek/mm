package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialConsumptionNormResponse extends BaseRecordDto {
    private UUID materialId;
    private UUID equipmentId;
    private UUID departmentId;
    private String productCode;
    private String operationCode;
    private String normType;
    private BigDecimal baseQuantity;
    private BigDecimal outputQuantity;
    private UUID unitOfMeasureId;
    private BigDecimal lossPercent;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}