package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import com.enterprise.mm.enums.MaterialType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DemandPlanItemCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID demandPlanId;
    private UUID departmentId;
    private UUID materialId;
    private MaterialType materialTypeSnapshot;
    private UUID budgetItemId;
    private LocalDate requiredDate;
    private LocalDate needByDate;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.Setter(lombok.AccessLevel.NONE)
    private boolean needByDateProvided;

    public void setNeedByDate(LocalDate needByDate) {
        this.needByDate = needByDate;
        this.needByDateProvided = true;
    }

    public boolean hasNeedByDate() {
        return needByDateProvided;
    }
    private String demandType;
    private String priority;
    private String repairOrderCode;
    private BigDecimal requestedQuantity;
    private BigDecimal approvedQuantity;
    private BigDecimal reservedQuantity;
    private BigDecimal estimatedUnitPrice;
    private BigDecimal estimatedAmount;
    private String currencyCode;
    private UUID consolidatedDemandId;
    private UUID purchaseRequestItemId;
    private List<DemandPlanItemMonthDto> months;
}

