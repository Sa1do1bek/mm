package com.enterprise.mm.payload;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IlliquidStockPolicyCreateRequest {
    private String code;

    @NotBlank
    private String name;

    private String description;
    private String legalEntityCode;
    private UUID warehouseId;
    private String materialCategory;
    private String materialType;

    @NotNull
    @Min(1)
    private Integer agingDays;

    @Min(0)
    private Integer excessDays;

    @NotNull
    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;
}
