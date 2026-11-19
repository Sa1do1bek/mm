package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import com.enterprise.mm.enums.MaterialType;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseRequestItemCreateRequest {
    private UUID id;
    private Long version;
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID purchaseRequestId;
    private String lineType;
    private UUID materialId;
    private MaterialType materialTypeSnapshot;
    private UUID serviceRequestId;
    private String serviceDescription;
    private String serviceCategory;
    private String unit;
    private String period;
    private String stage;
    private BigDecimal quantity;
    private BigDecimal estimatedPrice;
    private UUID budgetItemId;
}
