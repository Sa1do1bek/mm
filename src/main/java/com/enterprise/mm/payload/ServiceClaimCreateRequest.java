package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceClaimCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    private UUID serviceRequestId;
    private UUID serviceActId;
    private UUID serviceOrderId;
    private UUID purchaseOrderItemId;
    private UUID workCompletionActId;
    private UUID supplierId;
    private String claimType;
    private String severity;
    @NotNull
    private UUID assignedTo;
    private LocalDate deadline;
    private BigDecimal penaltyAmount;
    private String currencyCode;
    private String supplierResponse;
}
