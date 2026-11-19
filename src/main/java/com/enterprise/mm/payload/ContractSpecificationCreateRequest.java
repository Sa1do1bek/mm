package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import com.enterprise.mm.enums.MaterialType;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractSpecificationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID contractId;
    private String lineType;
    private UUID materialId;
    private UUID materialGroupId;
    private UUID unitOfMeasureId;
    private MaterialType materialTypeSnapshot;
    private String serviceDescription;
    private String serviceCategory;
    private String unit;
    private String period;
    private String stage;
    private BigDecimal serviceLimit;
    private String sla;
    private String pricingModel;
    private BigDecimal quantityLimit;
    private BigDecimal price;
}
