package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierKycCheckResponse extends BaseRecordDto {
    @NotNull
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    @NotNull
    @Size(max = 80)
    private String checkType;
    @Size(max = 120)
    private String provider;
    @Size(max = 160)
    private String externalCheckId;
    @Size(max = 40)
    private String riskLevel;
    private BigDecimal score;
    private LocalDateTime checkedAt;
    private LocalDate validUntil;
    private LocalDate nextReviewDate;
    private UUID evidenceAttachmentId;
    private String findings;
    private boolean remediationRequired;
}