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
public class DeliveryScheduleCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status;
    private String description;
    private UUID purchaseOrderId;
    private UUID contractId;
    private LocalDate scheduleDate;
    private BigDecimal plannedQuantity;
    private BigDecimal plannedAmount;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private BigDecimal receivedQuantity;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private BigDecimal receivedAmount;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private boolean overdueFlag;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime controlledAt;
    @Size(max = 160)
    private String businessImpactReference;
    private String businessImpactComment;
    private BigDecimal attributableShare;
    @Size(max = 3)
    private String impactCurrencyCode;
}
