package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierBankAccount;
import com.enterprise.mm.payload.SupplierBankAccountDto;
import com.enterprise.mm.payload.SupplierCreateRequest;
import com.enterprise.mm.payload.SupplierResponse;
import com.enterprise.mm.payload.SupplierUpdateRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class SupplierMapper {
    public SupplierResponse toResponse(Supplier entity) {
        if (entity == null) {
            return null;
        }
        SupplierResponse response = new SupplierResponse();
        response.setId(entity.getId());
        response.setTenantId(entity.getTenantId());
        response.setCompanyId(entity.getCompanyId());
        response.setCode(entity.getCode());
        response.setName(entity.getName());
        response.setStatus(entity.getStatus());
        response.setDescription(entity.getDescription());
        response.setDeleted(entity.isDeleted());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setCreatedBy(entity.getCreatedBy());
        response.setUpdatedBy(entity.getUpdatedBy());
        response.setVersion(entity.getVersion());
        response.setLegalName(entity.getLegalName());
        response.setShortName(entity.getShortName());
        response.setTaxId(entity.getTaxId());
        response.setBaseTaxId(entity.getBaseTaxId());
        response.setTaxIdSuffix(entity.getTaxIdSuffix());
        response.setParentSupplierId(entity.getParentSupplierId());
        response.setSupplierType(entity.getSupplierType());
        response.setRelationshipType(entity.getRelationshipType());
        response.setOwnerEmployeeId(entity.getOwnerEmployeeId());
        response.setOwnerDisplayName(entity.getOwnerEmployee() == null ? null : displayName(entity.getOwnerEmployee()));
        response.setPrimaryContactName(entity.getPrimaryContactName());
        response.setCountryCode(entity.getCountryCode());
        response.setCity(entity.getCity());
        response.setLegalForm(entity.getLegalForm());
        response.setBranchName(entity.getBranchName());
        response.setOkedCode(entity.getOkedCode());
        response.setVatRegistrationCode(entity.getVatRegistrationCode());
        response.setRegistrationNumber(entity.getRegistrationNumber());
        response.setRegistrationDate(entity.getRegistrationDate());
        response.setLegalAddress(entity.getLegalAddress());
        response.setActualAddress(entity.getActualAddress());
        response.setPhone(entity.getPhone());
        response.setEmail(entity.getEmail());
        response.setDirectorName(entity.getDirectorName());
        response.setChiefAccountantName(entity.getChiefAccountantName());
        response.setBankName(entity.getBankName());
        response.setBankMfo(entity.getBankMfo());
        response.setBankAccount(entity.getBankAccount());
        response.setBankAddress(entity.getBankAddress());
        response.setBankAccounts(toBankAccountResponses(entity));
        response.setCategory(entity.getCategory());
        response.setRating(entity.getRating());
        response.setBlockedReason(entity.getBlockedReason());
        return response;
    }

    public Supplier toEntity(SupplierCreateRequest request) {
        if (request == null) {
            return null;
        }
        Supplier entity = new Supplier();
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setStatus(request.getStatus());
        entity.setDescription(request.getDescription());
        entity.setLegalName(request.getLegalName());
        entity.setShortName(request.getShortName());
        entity.setTaxId(request.getTaxId());
        entity.setBaseTaxId(request.getBaseTaxId());
        entity.setTaxIdSuffix(request.getTaxIdSuffix());
        entity.setParentSupplierId(request.getParentSupplierId());
        entity.setSupplierType(request.getSupplierType());
        entity.setRelationshipType(request.getRelationshipType());
        entity.setOwnerEmployeeId(request.getOwnerEmployeeId());
        entity.setPrimaryContactName(request.getPrimaryContactName());
        entity.setCountryCode(request.getCountryCode());
        entity.setCity(request.getCity());
        entity.setLegalForm(request.getLegalForm());
        entity.setBranchName(request.getBranchName());
        entity.setOkedCode(request.getOkedCode());
        entity.setVatRegistrationCode(request.getVatRegistrationCode());
        entity.setRegistrationNumber(request.getRegistrationNumber());
        entity.setRegistrationDate(request.getRegistrationDate());
        entity.setLegalAddress(request.getLegalAddress());
        entity.setActualAddress(request.getActualAddress());
        entity.setPhone(normalizeOptionalPhone(request.getPhone()));
        entity.setEmail(request.getEmail());
        entity.setDirectorName(request.getDirectorName());
        entity.setChiefAccountantName(request.getChiefAccountantName());
        entity.setBankName(request.getBankName());
        entity.setBankMfo(request.getBankMfo());
        entity.setBankAccount(request.getBankAccount());
        entity.setBankAddress(request.getBankAddress());
        replaceBankAccounts(entity, request.getBankAccounts());
        entity.setCategory(request.getCategory());
        entity.setRating(request.getRating());
        entity.setBlockedReason(request.getBlockedReason());
        return entity;
    }

    public void updateEntity(Supplier entity, SupplierUpdateRequest request) {
        if (entity == null || request == null) {
            return;
        }
        // The supplier card is edited as a partial business profile. Identity and
        // workflow-owned fields (code, status, category, rating and block reason)
        // are deliberately excluded: they are changed only by their commands.
        if (request.getName() != null) entity.setName(request.getName());
        if (request.getDescription() != null) entity.setDescription(request.getDescription());
        if (request.getLegalName() != null) entity.setLegalName(request.getLegalName());
        if (request.getShortName() != null) entity.setShortName(request.getShortName());
        if (request.getTaxId() != null) entity.setTaxId(request.getTaxId());
        if (request.getBaseTaxId() != null) entity.setBaseTaxId(request.getBaseTaxId());
        if (request.getTaxIdSuffix() != null) entity.setTaxIdSuffix(request.getTaxIdSuffix());
        if (request.getParentSupplierId() != null) entity.setParentSupplierId(request.getParentSupplierId());
        if (request.getSupplierType() != null) entity.setSupplierType(request.getSupplierType());
        if (request.getRelationshipType() != null) entity.setRelationshipType(request.getRelationshipType());
        if (request.getOwnerEmployeeId() != null) entity.setOwnerEmployeeId(request.getOwnerEmployeeId());
        if (request.getPrimaryContactName() != null) entity.setPrimaryContactName(request.getPrimaryContactName());
        if (request.getCountryCode() != null) entity.setCountryCode(request.getCountryCode());
        if (request.getCity() != null) entity.setCity(request.getCity());
        if (request.getLegalForm() != null) entity.setLegalForm(request.getLegalForm());
        if (request.getBranchName() != null) entity.setBranchName(request.getBranchName());
        if (request.getOkedCode() != null) entity.setOkedCode(request.getOkedCode());
        if (request.getVatRegistrationCode() != null) entity.setVatRegistrationCode(request.getVatRegistrationCode());
        if (request.getRegistrationNumber() != null) entity.setRegistrationNumber(request.getRegistrationNumber());
        if (request.getRegistrationDate() != null) entity.setRegistrationDate(request.getRegistrationDate());
        if (request.getLegalAddress() != null) entity.setLegalAddress(request.getLegalAddress());
        if (request.getActualAddress() != null) entity.setActualAddress(request.getActualAddress());
        if (request.getPhone() != null) entity.setPhone(normalizeOptionalPhone(request.getPhone()));
        if (request.getEmail() != null) entity.setEmail(request.getEmail());
        if (request.getDirectorName() != null) entity.setDirectorName(request.getDirectorName());
        if (request.getChiefAccountantName() != null) entity.setChiefAccountantName(request.getChiefAccountantName());
        if (request.getBankName() != null) entity.setBankName(request.getBankName());
        if (request.getBankMfo() != null) entity.setBankMfo(request.getBankMfo());
        if (request.getBankAccount() != null) entity.setBankAccount(request.getBankAccount());
        if (request.getBankAddress() != null) entity.setBankAddress(request.getBankAddress());
        if (request.getBankAccounts() != null) {
            replaceBankAccounts(entity, request.getBankAccounts());
        }
    }

    private String normalizeOptionalPhone(String value) {
        if (value == null) return null;
        String phone = value.trim();
        // The fixed local input dial code is a placeholder, not a subscriber number.
        if (phone.isBlank() || phone.matches("\\+?998[\\s()-]*")) return null;
        return phone;
    }

    private List<SupplierBankAccountDto> toBankAccountResponses(Supplier entity) {
        if (entity.getBankAccounts() == null) {
            return List.of();
        }
        return entity.getBankAccounts().stream()
            .filter(account -> !account.isDeleted())
            .sorted(Comparator
                .comparing(SupplierBankAccount::isPrimaryAccount).reversed()
                .thenComparing(SupplierBankAccount::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(this::toBankAccountResponse)
            .toList();
    }

    private SupplierBankAccountDto toBankAccountResponse(SupplierBankAccount entity) {
        SupplierBankAccountDto response = new SupplierBankAccountDto();
        response.setBankName(entity.getBankName());
        response.setBankMfo(entity.getBankMfo());
        response.setBankAccount(entity.getBankAccount());
        response.setBankAddress(entity.getBankAddress());
        response.setPrimaryAccount(entity.isPrimaryAccount());
        return response;
    }

    private void replaceBankAccounts(Supplier entity, List<SupplierBankAccountDto> bankAccounts) {
        List<SupplierBankAccount> existingAccounts = entity.getBankAccounts();
        if (existingAccounts == null) {
            existingAccounts = new ArrayList<>();
            entity.setBankAccounts(existingAccounts);
        }
        if (bankAccounts == null) {
            return;
        }

        List<SupplierBankAccount> unmatchedAccounts = new ArrayList<>(existingAccounts);
        List<SupplierBankAccount> replacementAccounts = new ArrayList<>();
        for (SupplierBankAccountDto request : bankAccounts) {
            String requestedAccount = Supplier.normalizeBankAccountValue(request.getBankAccount());
            SupplierBankAccount account = unmatchedAccounts.stream()
                .filter(existing -> Objects.equals(
                    Supplier.normalizeBankAccountValue(existing.getBankAccount()), requestedAccount))
                .findFirst()
                .orElseGet(SupplierBankAccount::new);
            unmatchedAccounts.remove(account);
            account.setSupplier(entity);
            account.setBankName(request.getBankName());
            account.setBankMfo(request.getBankMfo());
            account.setBankAccount(request.getBankAccount());
            account.setBankAddress(request.getBankAddress());
            account.setPrimaryAccount(Boolean.TRUE.equals(request.getPrimaryAccount()));
            replacementAccounts.add(account);
        }

        try {
            existingAccounts.clear();
            existingAccounts.addAll(replacementAccounts);
        } catch (UnsupportedOperationException ex) {
            entity.setBankAccounts(replacementAccounts);
        }
    }

    private String displayName(com.enterprise.mm.common.BusinessEntity entity) {
        if (entity.getName() != null && !entity.getName().isBlank()) {
            return entity.getName();
        }
        return entity.getCode();
    }
}
