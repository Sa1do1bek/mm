package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierPortalSubmissionResponse extends BaseRecordDto {
    @NotNull
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID portalAccountId;
    private String portalAccountCode;
    private String portalAccountName;
    @Size(max = 80)
    private String submissionType;
    @Size(max = 80)
    private String referenceEntityType;
    private UUID referenceId;
    private String payloadJson;
    private UUID attachmentId;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
    @Size(max = 120)
    private String reviewedBy;
    @Size(max = 1000)
    private String reviewComment;
}