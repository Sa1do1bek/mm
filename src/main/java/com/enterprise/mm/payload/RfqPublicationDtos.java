package com.enterprise.mm.payload;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public final class RfqPublicationDtos {
    private RfqPublicationDtos() {
    }

    public record ValidationIssue(
        String code,
        String section,
        UUID supplierId,
        String messageKey
    ) {
    }

    public record SupplierPreview(
        UUID supplierId,
        String supplierName,
        String contactName,
        String email
    ) {
    }

    public record ArtifactPreview(String type, String fileName, String contentType) {
    }

    public record PublicationPreview(
        UUID rfqId,
        long expectedVersion,
        String language,
        int positionCount,
        int supplierCount,
        List<SupplierPreview> suppliers,
        List<ArtifactPreview> artifacts,
        List<ValidationIssue> issues,
        boolean publishable
    ) {
        public PublicationPreview {
            suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    public record PublishRfqRequest(@NotNull Long expectedVersion) {
    }

    public record ResendDeliveryRequest(
        @NotNull Long expectedVersion,
        @Email String correctedRecipientEmail,
        String reason
    ) {
    }

    public record ResendFailedResponse(int queuedCount) {
    }

    public record PublishRfqResponse(
        UUID rfqId,
        UUID publishedVersionId,
        int versionNumber,
        int queuedDeliveryCount,
        String status
    ) {
    }
}
