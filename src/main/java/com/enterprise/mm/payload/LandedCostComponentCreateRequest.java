package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LandedCostComponentCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status;
    private String description;
    private UUID importDeliveryId;
    private String componentType;
    private BigDecimal amount;
    private String currencyCode;
    private String allocationMethod;
    private UUID supplierId;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private boolean includedInInventoryCost;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime postedAt;
}
