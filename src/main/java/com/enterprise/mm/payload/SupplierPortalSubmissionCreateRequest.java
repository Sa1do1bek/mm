package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierPortalSubmissionCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    @JsonIgnore
    private String status;
    private String description;
    @NotNull
    private UUID supplierId;
    private UUID portalAccountId;
    @Size(max = 80)
    private String submissionType;
    @Size(max = 80)
    private String referenceEntityType;
    private UUID referenceId;
    private String payloadJson;
    private UUID attachmentId;
    @JsonIgnore
    private LocalDateTime submittedAt;
    @JsonIgnore
    private LocalDateTime reviewedAt;
    @Size(max = 120)
    @JsonIgnore
    private String reviewedBy;
    @Size(max = 1000)
    @JsonIgnore
    private String reviewComment;
}
