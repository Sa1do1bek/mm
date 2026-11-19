package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ProcurementCaseDtos {
    private ProcurementCaseDtos() {
    }

    public record ProcurementCaseResponse(
        String rootType,
        UUID rootId,
        DocumentRef root,
        String currentStage,
        String sourceQuality,
        List<StageRef> stages,
        List<DocumentRef> relatedDocuments,
        List<SourceWarning> sourceWarnings,
        LocalDateTime generatedAt
    ) {
    }

    public record StageRef(
        String key,
        String state,
        List<DocumentRef> documents
    ) {
    }

    public record DocumentRef(
        String documentType,
        UUID documentId,
        String moduleKey,
        String code,
        String displayName,
        String status
    ) {
    }

    public record SourceWarning(
        String code,
        String message,
        UUID sourceId
    ) {
    }
}
