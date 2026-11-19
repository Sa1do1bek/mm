package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialAnalogCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
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
