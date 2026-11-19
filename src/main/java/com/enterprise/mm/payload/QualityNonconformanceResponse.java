package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QualityNonconformanceResponse extends BaseRecordDto {
    private UUID qualityInspectionId;
    private String qualityInspectionCode;
    private String qualityInspectionName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private UUID stockMovementId;
    private String stockMovementCode;
    private String stockMovementName;
    private String severity;
    private String nonconformanceType;
    private BigDecimal detectedQuantity;
    private String defectDescription;
    private String rootCause;
    private String disposition;
    private boolean capaRequired;
    private UUID claimId;
    private String claimCode;
    private String claimName;
    private UUID correctiveActionId;
    private String correctiveActionCode;
    private String correctiveActionName;
    private LocalDateTime detectedAt;
    private LocalDateTime closedAt;
}