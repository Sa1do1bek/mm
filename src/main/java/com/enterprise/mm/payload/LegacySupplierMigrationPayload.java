package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.util.List;

/** Legacy evidence may lack registration/contact fields; imported suppliers remain unaccredited. */
public record LegacySupplierMigrationPayload(
    @NotBlank @Size(max = 80) String code,
    @NotBlank @Size(max = 240) String name,
    @NotBlank @Size(max = 300) String legalName,
    @Size(max = 180) String shortName,
    @Size(max = 500) String taxId,
    @Size(max = 80) String registrationNumber,
    @Size(max = 500) String legalAddress,
    @Size(max = 500) String actualAddress,
    @Size(max = 80) String phone,
    @Size(max = 160) String email,
    @Size(max = 240) String directorName,
    @Size(max = 500) String vatRegistrationCode,
    @Size(max = 100) List<@Valid SupplierBankAccountDto> bankAccounts,
    boolean primaryBankAccountConfirmed,
    @Size(max = 16000) String description,
    boolean markedForDeletion
) {
    public SupplierCreateRequest toCreateRequest() {
        SupplierCreateRequest request = new SupplierCreateRequest();
        request.setCode(code); request.setName(name); request.setLegalName(legalName);
        request.setShortName(shortName); request.setTaxId(taxId);
        request.setRegistrationNumber(registrationNumber); request.setLegalAddress(legalAddress);
        request.setActualAddress(actualAddress); request.setPhone(phone); request.setEmail(email);
        request.setDirectorName(directorName); request.setVatRegistrationCode(vatRegistrationCode);
        request.setBankAccounts(bankAccounts);
        request.setDescription(description);
        return request;
    }
}
