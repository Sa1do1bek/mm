package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QualityClaimCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String claimNumber;
    private UUID supplierId;
    private UUID qualityNonconformanceId;
    private UUID qualityInspectionId;
    private UUID purchaseOrderId;
    private UUID materialId;
    private LocalDate claimDate;
    private BigDecimal claimAmount;
    private String currencyCode;
    private String requestedAction;
    private String supplierResponse;
    private LocalDate responseDueDate;
    private UUID correctiveActionId;
    private LocalDateTime sentAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime closedAt;
}
