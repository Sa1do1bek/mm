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
public class ImportInsurancePolicyCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status;
    private String description;
    private String policyNumber;
    private UUID importDeliveryId;
    private UUID insurerSupplierId;
    private String insurerName;
    private String coverageType;
    private BigDecimal insuredAmount;
    private BigDecimal premiumAmount;
    private BigDecimal deductibleAmount;
    private String currencyCode;
    private String allocationMethod;
    private boolean includedInInventoryCost;
    private LocalDate validFrom;
    private LocalDate validTo;
    private UUID policyAttachmentId;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID landedCostComponentId;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime activatedAt;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime claimedAt;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime closedAt;
}
