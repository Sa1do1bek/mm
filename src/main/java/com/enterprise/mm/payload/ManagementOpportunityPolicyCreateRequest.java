package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ManagementOpportunityType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManagementOpportunityPolicyCreateRequest {
    @Size(max = 80)
    private String code;

    @NotBlank
    @Size(max = 240)
    private String name;

    private String description;

    @NotNull
    private ManagementOpportunityType opportunityType;

    @NotNull
    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;
    private UUID warehouseId;
    private UUID materialGroupId;
    private UUID supplierId;

    @DecimalMin(value = "0.000000", inclusive = false)
    @DecimalMax("1.000000")
    private BigDecimal annualCarryingCostRate;

    @DecimalMin(value = "0.0000", inclusive = false)
    private BigDecimal dailyImpactRate;

    @DecimalMin(value = "0.000000", inclusive = false)
    @DecimalMax("1.000000")
    private BigDecimal attributableShare;

    @Size(min = 3, max = 3)
    private String currencyCode;
}
