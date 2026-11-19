package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QualityClaimResponse extends BaseRecordDto {
    private String claimNumber;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID qualityNonconformanceId;
    private String qualityNonconformanceCode;
    private String qualityNonconformanceName;
    private UUID qualityInspectionId;
    private String qualityInspectionCode;
    private String qualityInspectionName;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private LocalDate claimDate;
    private BigDecimal claimAmount;
    private String currencyCode;
    private String requestedAction;
    private String supplierResponse;
    private LocalDate responseDueDate;
    private UUID correctiveActionId;
    private String correctiveActionCode;
    private String correctiveActionName;
    private LocalDateTime sentAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime closedAt;
}