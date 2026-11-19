package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseRequestItemResponse extends BaseRecordDto {
    private UUID purchaseRequestId;
    private String purchaseRequestCode;
    private String purchaseRequestName;
    private String lineType;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private MaterialType materialTypeSnapshot;
    private UUID serviceRequestId;
    private String serviceDescription;
    private String serviceCategory;
    private String unit;
    private String unitShortName;
    private String period;
    private String stage;
    private BigDecimal quantity;
    private BigDecimal estimatedPrice;
    private java.time.LocalDate requiredDate;
    private UUID budgetItemId;
    private String budgetItemCode;
    private String budgetItemName;
    private UUID sourceDemandPlanItemId;
    private String sourceDemandPlanItemCode;
    private String sourceDemandPlanItemName;
    private UUID sourceDemandPlanId;
    private String sourceDemandPlanCode;
    private String sourceDemandPlanName;
}
