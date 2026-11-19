package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ReferenceMappingAiDtos {
    private ReferenceMappingAiDtos() {
    }

    public record ReferenceMappingAiAnalyzeRequest(
        @NotBlank String referenceEntityType,
        @NotBlank String externalSystem,
        String accountingArea,
        String providerSystem,
        Boolean onlyUnmapped,
        Integer limit
    ) {
    }

    public record ReferenceMappingAiSuggestionDto(
        UUID referenceId,
        String referenceCode,
        String referenceName,
        String externalCode,
        String externalName,
        String accountingArea,
        String chartOfAccountsCode,
        String accountCode,
        String subAccountCode,
        String costCenter,
        String profitCenter,
        String warehouseAccountingCode,
        String taxCode,
        String currencyCode,
        Integer priority,
        Boolean defaultMapping,
        Double confidence,
        String reason,
        String source,
        UUID existingMappingId,
        Boolean existingMapping
    ) {
    }

    public record ReferenceMappingAiRelationDto(
        String sourceReferenceEntityType,
        UUID sourceReferenceId,
        String sourceReferenceCode,
        String targetReferenceEntityType,
        UUID targetReferenceId,
        String targetReferenceCode,
        String relationType,
        Double confidence,
        String reason
    ) {
    }

    public record ReferenceMappingAiAnalyzeResponse(
        String providerSystem,
        String providerStatus,
        String providerMessage,
        String providerModel,
        String referenceEntityType,
        String externalSystem,
        String accountingArea,
        int analyzedCount,
        List<ReferenceMappingAiSuggestionDto> suggestions,
        List<ReferenceMappingAiRelationDto> relations,
        LocalDateTime generatedAt
    ) {
    }

    public record ReferenceMappingAiApplyRequest(
        @NotBlank String referenceEntityType,
        @NotBlank String externalSystem,
        String accountingArea,
        @NotEmpty List<ReferenceMappingAiSuggestionDto> suggestions
    ) {
    }

    public record ReferenceMappingBulkFailure(
        String itemKey,
        UUID referenceId,
        String referenceCode,
        String externalCode,
        String message
    ) {
    }

    public record ReferenceMappingBulkResult(
        int applied,
        int skipped,
        List<ReferenceMappingBulkFailure> failures,
        LocalDateTime appliedAt
    ) {
    }

    public record ReferenceMappingAiApplyResponse(
        int applied,
        int created,
        int updated,
        int skipped,
        List<ReferenceMappingBulkFailure> failures,
        List<ReferenceMappingResponse> mappings,
        LocalDateTime appliedAt
    ) {
    }
}
