package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierLicenseCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    @NotNull
    private UUID supplierId;
    @NotBlank
    @Size(max = 120)
    private String licenseNumber;
    @Size(max = 120)
    private String licenseType;
    @Size(max = 240)
    private String issuingAuthority;
    private LocalDate issueDate;
    private LocalDate validFrom;
    private LocalDate validTo;
    @Size(max = 500)
    private String scope;
    @Size(max = 240)
    private String territory;
    private boolean renewalRequired;
    private LocalDate revokedAt;
    @Size(max = 500)
    private String revocationReason;
}
