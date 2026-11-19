package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DemandPlanningCycleCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    private Integer planYear;
    private String periodType;
    private Integer quarter;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate collectionStartDate;
    private LocalDate collectionEndDate;
    private LocalDate budgetReviewDeadline;
    private LocalDate consolidationDeadline;
    private LocalDate activationDate;
    private UUID ownerEmployeeId;
    private String currencyCode;
}
