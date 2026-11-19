package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReferenceMappingCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String referenceEntityType;
    private UUID referenceId;
    private String referenceCode;
    private String referenceName;
    private String externalSystem;
    private String externalCode;
    private String externalName;
    private String accountingArea;
    private String chartOfAccountsCode;
    private String accountCode;
    private String subAccountCode;
    private String costCenter;
    private String profitCenter;
    private String warehouseAccountingCode;
    private String taxCode;
    private String currencyCode;
    private int priority;
    private boolean defaultMapping;
    private LocalDate validFrom;
    private LocalDate validTo;
}
