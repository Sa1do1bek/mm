package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseOrderCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID supplierId;
    private UUID purchaseRequestId;
    private UUID contractId;
    private LocalDate orderDate;
    private LocalDate expectedDeliveryDate;
    @Size(max = 20)
    private String requestType;
    private BigDecimal totalAmount;
    private String currencyCode;
    private BigDecimal exchangeRate;
    @Valid
    private List<PurchaseOrderItemCreateRequest> items;
}
