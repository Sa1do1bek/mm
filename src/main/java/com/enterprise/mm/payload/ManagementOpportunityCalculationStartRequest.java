package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ManagementOpportunityType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ManagementOpportunityCalculationStartRequest {
    @NotNull
    private LocalDate asOfDate;

    @Size(min = 1)
    private List<ManagementOpportunityType> types;

    @Size(min = 3, max = 3)
    private String targetCurrencyCode = "UZS";
}
