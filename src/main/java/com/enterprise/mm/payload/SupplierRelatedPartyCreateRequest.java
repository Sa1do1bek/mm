package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierRelatedPartyCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    @NotNull
    private UUID supplierId;
    @NotNull
    @Size(max = 80)
    private String partyType;
    @Size(max = 240)
    private String fullName;
    @Size(max = 300)
    private String legalName;
    @Size(max = 500)
    private String taxId;
    @Size(max = 3)
    private String countryCode;
    private BigDecimal ownershipPercent;
    @Size(max = 240)
    private String positionTitle;
    @Size(max = 80)
    private String phone;
    @Size(max = 160)
    private String email;
    private boolean pepFlag;
    private boolean sanctionsMatch;
    private boolean conflictOfInterestFlag;
    @Size(max = 40)
    private String riskLevel;
    private LocalDate validFrom;
    private LocalDate validTo;
}
