package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialConsumptionNormCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
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
