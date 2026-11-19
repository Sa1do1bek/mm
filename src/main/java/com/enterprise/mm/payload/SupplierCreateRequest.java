package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierCreateRequest {
    @NotBlank
    @Size(max = 300)
    private String legalName;
    @Size(max = 180)
    private String shortName;
    @NotBlank
    @Size(max = 500)
    private String taxId;
    @Size(max = 500)
    private String baseTaxId;
    private Integer taxIdSuffix;
    private UUID parentSupplierId;
    @Size(max = 40)
    private String supplierType;
    @Size(max = 20)
    private String relationshipType;
    private UUID ownerEmployeeId;
    @Size(max = 240)
    private String primaryContactName;
    @Size(max = 3)
    private String countryCode;
    @Size(max = 160)
    private String city;
    @Size(max = 80)
    private String legalForm;
    @Size(max = 240)
    private String branchName;
    private Boolean createAsBranch;
    @Size(max = 500)
    private String okedCode;
    @Size(max = 500)
    private String vatRegistrationCode;
    @NotBlank
    @Size(max = 80)
    private String registrationNumber;
    private LocalDate registrationDate;
    @NotBlank
    @Size(max = 500)
    private String legalAddress;
    @Size(max = 500)
    private String actualAddress;
    @Size(max = 80)
    private String phone;
    @NotBlank
    @Size(max = 160)
    private String email;
    @Size(max = 240)
    private String directorName;
    @Size(max = 240)
    private String chiefAccountantName;
    @Size(max = 240)
    private String bankName;
    @Size(max = 500)
    private String bankMfo;
    @Size(max = 500)
    private String bankAccount;
    @Size(max = 500)
    private String bankAddress;
    private List<SupplierBankAccountDto> bankAccounts;
    @Size(max = 5)
    private String category;
    @PositiveOrZero
    private BigDecimal rating;
    @Size(max = 500)
    private String blockedReason;
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
}
