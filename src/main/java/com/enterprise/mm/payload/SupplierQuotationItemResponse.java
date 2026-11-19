package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialType;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierQuotationItemResponse extends BaseRecordDto {
    private UUID quotationId;
    private String quotationCode;
    private String quotationName;
    private UUID rfqItemId;
    private String rfqItemCode;
    private String rfqItemName;
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
    private String pricingModel;
    private UUID budgetItemId;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private Integer deliveryDays;
}
