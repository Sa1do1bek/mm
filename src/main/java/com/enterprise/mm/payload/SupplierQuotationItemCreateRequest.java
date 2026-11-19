package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import com.enterprise.mm.enums.MaterialType;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierQuotationItemCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID quotationId;
    private UUID rfqItemId;
    private String lineType;
    private UUID materialId;
    private MaterialType materialTypeSnapshot;
    private UUID serviceRequestId;
    private String serviceDescription;
    private String serviceCategory;
    private String unit;
    private String period;
    private String stage;
    private String pricingModel;
    private UUID budgetItemId;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private Integer deliveryDays;
}
