package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialType;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractSpecificationResponse extends BaseRecordDto {
    private UUID contractId;
    private String lineType;
    private UUID materialId;
    private UUID materialGroupId;
    private UUID unitOfMeasureId;
    private MaterialType materialTypeSnapshot;
    private String serviceDescription;
    private String serviceCategory;
    private String unit;
    private String unitShortName;
    private String period;
    private String stage;
    private BigDecimal serviceLimit;
    private String sla;
    private String pricingModel;
    private BigDecimal quantityLimit;
    private BigDecimal price;
    private UUID sourceRfqItemId;
    private UUID sourceQuotationItemId;
    private UUID sourcePurchaseRequestItemId;
}
