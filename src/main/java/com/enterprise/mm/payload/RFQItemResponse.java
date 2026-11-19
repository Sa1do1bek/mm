package com.enterprise.mm.payload;

import com.enterprise.mm.enums.MaterialType;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RFQItemResponse extends BaseRecordDto {
    private UUID rfqId;
    private String rfqCode;
    private String rfqName;
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
    private UUID budgetItemId;
    private BigDecimal quantity;
}
