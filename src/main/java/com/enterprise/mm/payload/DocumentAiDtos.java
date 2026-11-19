package com.enterprise.mm.payload;

import com.enterprise.mm.payload.ContractAiDtos.ContractAiFindingDto;
import com.enterprise.mm.payload.ContractAiDtos.ContractAiRecommendationDto;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class DocumentAiDtos {
    private DocumentAiDtos() {
    }

    public record DocumentAiAnalyzeRequest(
        String entityType,
        UUID entityId,
        String providerSystem,
        Boolean includeAttachments,
        Boolean includeRelatedDocuments
    ) {
    }

    public record DocumentAiAttachmentDto(
        UUID id,
        String fileName,
        String attachmentType,
        String contentType,
        Long sizeBytes,
        String checksumSha256
    ) {
    }

    public record DocumentAiAnalyzeResponse(
        String providerSystem,
        String providerStatus,
        String providerMessage,
        String providerModel,
        String entityType,
        UUID entityId,
        String documentNumber,
        String documentName,
        String documentStatus,
        String summary,
        Object extractedFields,
        List<DocumentAiAttachmentDto> attachments,
        List<ContractAiFindingDto> findings,
        List<ContractAiRecommendationDto> recommendations,
        Object context,
        LocalDateTime generatedAt
    ) {
    }
}