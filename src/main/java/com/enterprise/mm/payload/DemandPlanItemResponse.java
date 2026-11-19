package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DemandPlanItemResponse extends BaseRecordDto {
    private UUID demandPlanId;
    private String demandPlanCode;
    private String demandPlanName;
    private UUID departmentId;
    private String departmentCode;
    private String departmentName;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private MaterialType materialTypeSnapshot;
    private UUID budgetItemId;
    private String budgetItemCode;
    private String budgetItemName;
    private LocalDate requiredDate;
    private LocalDate needByDate;
    private String demandType;
    private String priority;
    private String projectCode;
    private String repairOrderCode;
    private BigDecimal requestedQuantity;
    private BigDecimal approvedQuantity;
    private BigDecimal reservedQuantity;
    private BigDecimal estimatedUnitPrice;
    private BigDecimal estimatedAmount;
    private String currencyCode;
    private UUID consolidatedDemandId;
    private String consolidatedDemandCode;
    private String consolidatedDemandName;
    private UUID purchaseRequestItemId;
    private String purchaseRequestItemCode;
    private String purchaseRequestItemName;
    private List<DemandPlanItemMonthDto> months;
}
