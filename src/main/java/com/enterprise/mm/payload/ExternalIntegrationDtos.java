package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ExternalIntegrationDtos {
    private ExternalIntegrationDtos() {
    }

    public record IntegrationSystemDto(
        String code,
        String name,
        String description,
        boolean enabled,
        boolean configured,
        boolean mockMode,
        String status,
        String baseUrl,
        List<String> capabilities,
        long pendingEvents,
        String lastEventStatus,
        LocalDateTime lastEventAt
    ) {
    }

    public record IntegrationHealthDto(
        String code,
        boolean enabled,
        boolean configured,
        boolean mockMode,
        String status,
        String message,
        LocalDateTime checkedAt
    ) {
    }

    public record IntegrationSendRequest(
        @NotBlank String eventType,
        UUID aggregateId,
        @NotNull Object payload,
        String idempotencyKey
    ) {
    }

    public record IntegrationSyncTestRequest(
        String scenario,
        Object payload,
        String idempotencyKey
    ) {
    }

    public record IntegrationProviderRequest(
        String action,
        Object payload,
        String idempotencyKey
    ) {
    }

    public record AiPromptRequest(
        String prompt,
        Object context
    ) {
    }

    public record AiPromptResponse(
        String system,
        String provider,
        String model,
        String status,
        String message,
        String response,
        Object raw,
        LocalDateTime checkedAt
    ) {
    }

    public record IntegrationCancelRequest(
        String reason,
        Object payload,
        String idempotencyKey
    ) {
    }

    public record IntegrationDocumentStatusDto(
        String system,
        String externalId,
        String status,
        String providerStatus,
        String message,
        Object payload,
        LocalDateTime checkedAt
    ) {
    }

    public record IntegrationDocumentSummaryDto(
        String externalId,
        String documentNumber,
        String documentDate,
        String documentType,
        String status,
        Object payload
    ) {
    }

    public record IntegrationDocumentListDto(
        String system,
        String direction,
        List<IntegrationDocumentSummaryDto> documents,
        Object payload,
        LocalDateTime checkedAt
    ) {
    }

    public record IntegrationDownloadResult(
        String fileName,
        String contentType,
        byte[] content,
        AttachmentDto archivedAttachment
    ) {
    }

    public record IntegrationArchivedDocumentDto(
        String system,
        String externalId,
        UUID entityId,
        AttachmentDto attachment,
        LocalDateTime archivedAt
    ) {
    }

    public record IntegrationCallbackRequest(
        String eventType,
        String externalId,
        String status,
        Object payload,
        String idempotencyKey,
        UUID aggregateId
    ) {
    }

    public record IntegrationCallbackResponse(
        UUID integrationEventId,
        String system,
        String externalId,
        String status,
        String message
    ) {
    }

    public record EImzoDocumentSignatureRequest(
        String entityType,
        UUID entityId,
        String signaturePayload,
        String pkcs7,
        String certificateSerial,
        String signerTin,
        Object payload
    ) {
    }

    public record EImzoDocumentSignatureResponse(
        String entityType,
        UUID entityId,
        boolean verified,
        String status,
        LocalDateTime signedAt,
        UUID integrationEventId
    ) {
    }
}
