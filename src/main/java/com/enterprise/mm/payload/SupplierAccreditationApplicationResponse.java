package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierAccreditationApplicationResponse extends BaseRecordDto {
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String accreditationType;
    private String requestedScope;
    private List<UUID> requestedMaterialGroupIds;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewStartedAt;
    private LocalDate validFrom;
    private LocalDate validTo;
    private BigDecimal riskScore;
    private String reviewComment;
    private String complianceRecommendation;
    private String complianceSnapshotJson;
    private UUID parentApplicationId;
    private String parentApplicationCode;
    private String parentApplicationName;
    private List<String> allowedActions;
    private UUID approvalInstanceId;
    private String approvalStatus;
}
