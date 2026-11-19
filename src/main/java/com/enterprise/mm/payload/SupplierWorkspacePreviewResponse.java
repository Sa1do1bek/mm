package com.enterprise.mm.payload;

import com.enterprise.mm.supplier.governance.payload.SupplierGovernanceDtos.EntityRef;
import java.util.List;

public record SupplierWorkspacePreviewResponse(
    NormalizedSupplierDraft normalized,
    boolean exactDuplicate,
    List<DuplicateCandidate> duplicates,
    PolicyImpact policyImpact
) {
    public record NormalizedSupplierDraft(
        String legalName,
        String shortName,
        String taxId,
        String registrationNumber,
        String bankAccount,
        String relationshipType,
        String supplierType,
        List<String> requestedAccreditationTypes
    ) {
    }

    public record DuplicateCandidate(
        EntityRef supplier,
        String confidence,
        List<String> matchReasons
    ) {
    }

    public record RequirementTemplateSummary(
        String code,
        String displayName,
        String status
    ) {
    }

    public record PolicyImpact(
        boolean relationshipChanged,
        boolean readinessBypass,
        List<String> requiredProfileFields,
        List<RequirementTemplateSummary> evidenceTemplates,
        List<String> accreditationTypes
    ) {
    }
}
