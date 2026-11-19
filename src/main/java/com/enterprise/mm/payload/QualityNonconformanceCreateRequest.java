package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QualityNonconformanceCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID qualityInspectionId;
    private UUID supplierId;
    private UUID purchaseOrderId;
    private UUID materialId;
    private UUID stockMovementId;
    private String severity;
    private String nonconformanceType;
    private BigDecimal detectedQuantity;
    private String defectDescription;
    private String rootCause;
    private String disposition;
    private boolean capaRequired;
    private UUID claimId;
    private UUID correctiveActionId;
    private LocalDateTime detectedAt;
    private LocalDateTime closedAt;
}
