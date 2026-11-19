package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceOrderCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    private UUID serviceRequestId;
    private UUID supplierId;
    private UUID contractId;
    private UUID purchaseOrderId;
    private String serviceType;
    private BigDecimal totalAmount;
    private String currencyCode;
}
