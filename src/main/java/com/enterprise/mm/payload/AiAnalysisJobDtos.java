package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;

public final class AiAnalysisJobDtos {
    private AiAnalysisJobDtos() {
    }

    public record AiAnalysisJobResponse(
        UUID id,
        String analysisType,
        String entityType,
        UUID entityId,
        String status,
        String requestedBy,
        String requestedLanguage,
        String requestHash,
        Object result,
        String errorCode,
        String errorMessage,
        String provider,
        String model,
        String modelVersion,
        LocalDateTime requestedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime updatedAt
    ) {
    }
}
