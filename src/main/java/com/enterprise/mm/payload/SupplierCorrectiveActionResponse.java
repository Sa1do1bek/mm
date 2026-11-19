package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierCorrectiveActionResponse extends BaseRecordDto {
    @NotNull
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    @Size(max = 80)
    private String sourceType;
    private UUID sourceId;
    @Size(max = 240)
    private String issueTitle;
    private String issueDescription;
    private String rootCause;
    private String correctiveAction;
    private String preventiveAction;
    private UUID ownerEmployeeId;
    private String ownerEmployeeCode;
    private String ownerEmployeeName;
    @Size(max = 240)
    private String supplierResponsibleName;
    private LocalDate dueDate;
    private LocalDateTime submittedAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime closedAt;
    @Size(max = 40)
    private String severity;
    @Size(max = 1000)
    private String verificationResult;
    private boolean recurrencePrevented;
}