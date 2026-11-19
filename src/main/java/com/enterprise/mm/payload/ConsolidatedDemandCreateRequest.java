package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConsolidatedDemandCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID demandPlanId;
    private UUID materialId;
    private UUID budgetItemId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String highestPriority;
    private BigDecimal totalRequestedQuantity;
    private BigDecimal totalApprovedQuantity;
    private BigDecimal totalEstimatedAmount;
    private String currencyCode;
    private Integer sourceItemCount;
    private UUID purchaseRequestId;
    private LocalDateTime consolidatedAt;
}
