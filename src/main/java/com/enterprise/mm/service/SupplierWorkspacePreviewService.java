package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.SupplierRelationshipType;
import com.enterprise.mm.payload.SupplierWorkspacePreviewRequest;
import com.enterprise.mm.payload.SupplierWorkspacePreviewResponse;
import com.enterprise.mm.payload.SupplierWorkspacePreviewResponse.DuplicateCandidate;
import com.enterprise.mm.payload.SupplierWorkspacePreviewResponse.NormalizedSupplierDraft;
import com.enterprise.mm.payload.SupplierWorkspacePreviewResponse.PolicyImpact;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.EntityRef;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierWorkspacePreviewService {
    private final SupplierRepository supplierRepository;

    @Transactional(readOnly = true)
    public SupplierWorkspacePreviewResponse preview(SupplierWorkspacePreviewRequest request) {
        SupplierWorkspacePreviewRequest safe = request == null
            ? new SupplierWorkspacePreviewRequest(null, null, null, null, null, null, null, null, List.of())
            : request;
        NormalizedSupplierDraft normalized = normalize(safe);
        List<DuplicateCandidate> duplicates = supplierRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .stream()
            .filter(supplier -> safe.supplierId() == null || !safe.supplierId().equals(supplier.getId()))
            .map(supplier -> duplicateCandidate(supplier, normalized))
            .filter(Objects::nonNull)
            .toList();
        boolean exactDuplicate = duplicates.stream()
            .anyMatch(candidate -> "EXACT".equals(candidate.confidence()));
        return new SupplierWorkspacePreviewResponse(
            normalized,
            exactDuplicate,
            duplicates,
            policyImpact(safe, normalized)
        );
    }

    private NormalizedSupplierDraft normalize(SupplierWorkspacePreviewRequest request) {
        String relationshipType = normalizeRelationshipType(request.relationshipType());
        return new NormalizedSupplierDraft(
            trimToNull(request.legalName()),
            trimToNull(request.shortName()),
            normalizeCompact(request.taxId()),
            normalizeCompact(request.registrationNumber()),
            Supplier.normalizeBankAccountValue(request.bankAccount()),
            relationshipType,
            upperOrNull(request.supplierType()),
            normalizeTypes(request.requestedAccreditationTypes())
        );
    }

    private DuplicateCandidate duplicateCandidate(Supplier supplier, NormalizedSupplierDraft draft) {
        Set<String> reasons = new LinkedHashSet<>();
        if (draft.taxId() != null && Objects.equals(draft.taxId(), normalizeCompact(supplier.getTaxId()))) {
            reasons.add("TAX_ID");
        }
        if (draft.registrationNumber() != null
            && Objects.equals(draft.registrationNumber(), normalizeCompact(supplier.getRegistrationNumber()))) {
            reasons.add("REGISTRATION_NUMBER");
        }
        String draftBankHash = draft.bankAccount() == null ? null : Supplier.sha256(draft.bankAccount());
        if (draftBankHash != null && Objects.equals(draftBankHash, supplier.getBankAccountHash())) {
            reasons.add("BANK_ACCOUNT");
        }
        boolean exact = reasons.stream().anyMatch(List.of("TAX_ID", "REGISTRATION_NUMBER", "BANK_ACCOUNT")::contains);
        if (!exact && possibleNameMatch(supplier, draft)) {
            reasons.add("NAME");
        }
        if (reasons.isEmpty()) {
            return null;
        }
        return new DuplicateCandidate(
            new EntityRef(supplier.getId(), supplier.getCode(), displayName(supplier), supplier.getStatus()),
            exact ? "EXACT" : "POSSIBLE",
            List.copyOf(reasons)
        );
    }

    private PolicyImpact policyImpact(SupplierWorkspacePreviewRequest request, NormalizedSupplierDraft draft) {
        boolean relationshipChanged = request.supplierId() != null
            || SupplierRelationshipType.INTERNAL.name().equals(draft.relationshipType());
        List<String> required = new ArrayList<>();
        if (draft.legalName() == null) {
            required.add("legalName");
        }
        if (draft.taxId() == null) {
            required.add("taxId");
        }
        if (draft.relationshipType() == null) {
            required.add("relationshipType");
        }
        return new PolicyImpact(
            relationshipChanged,
            false,
            List.copyOf(required),
            List.of(),
            draft.requestedAccreditationTypes()
        );
    }

    private boolean possibleNameMatch(Supplier supplier, NormalizedSupplierDraft draft) {
        String candidate = normalizeName(firstNonBlank(supplier.getLegalName(), supplier.getShortName(), supplier.getName()));
        return possibleName(candidate, normalizeName(draft.legalName()))
            || possibleName(candidate, normalizeName(draft.shortName()));
    }

    private boolean possibleName(String left, String right) {
        if (left == null || right == null || left.length() < 4 || right.length() < 4) {
            return false;
        }
        return left.contains(right) || right.contains(left);
    }

    private String normalizeRelationshipType(String value) {
        if (value == null || value.isBlank()) {
            return SupplierRelationshipType.EXTERNAL.name();
        }
        return SupplierRelationshipType.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
    }

    private List<String> normalizeTypes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(this::upperOrNull)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private String normalizeCompact(String value) {
        return Supplier.normalizeBankAccountValue(value);
    }

    private String upperOrNull(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private String normalizeName(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ");
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String displayName(Supplier supplier) {
        return firstNonBlank(supplier.getName(), supplier.getLegalName(), supplier.getCode());
    }
}
