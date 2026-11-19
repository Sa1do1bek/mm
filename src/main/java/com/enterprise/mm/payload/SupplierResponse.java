package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierResponse extends BaseRecordDto {
    private SupplierEligibilityDtos.UnifiedSupplierEligibility eligibility;
    private String legalName;
    private String shortName;
    private String taxId;
    private String baseTaxId;
    private Integer taxIdSuffix;
    private UUID parentSupplierId;
    private String supplierType;
    private String relationshipType;
    private UUID ownerEmployeeId;
    private String ownerDisplayName;
    private String primaryContactName;
    private String countryCode;
    private String city;
    private String legalForm;
    private String branchName;
    private String okedCode;
    private String vatRegistrationCode;
    private String registrationNumber;
    private LocalDate registrationDate;
    private String legalAddress;
    private String actualAddress;
    private String phone;
    private String email;
    private String directorName;
    private String chiefAccountantName;
    private String bankName;
    private String bankMfo;
    private String bankAccount;
    private String bankAddress;
    private List<SupplierBankAccountDto> bankAccounts;
    private String category;
    private BigDecimal rating;
    private String blockedReason;
}
