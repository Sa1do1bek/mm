package com.enterprise.mm.payload;

import com.enterprise.mm.enums.ExternalIntegrationSystem;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class InboundIntegrationDtos {
    private InboundIntegrationDtos() {
    }

    public record InboundSyncRequest(
        List<String> systems,
        List<String> datasets,
        Map<String, String> paths,
        Boolean requireLive,
        Boolean archiveOriginals,
        Integer limit
    ) {
    }

    public record InboundSyncResult(
        LocalDateTime syncedAt,
        int systemsProcessed,
        int received,
        int created,
        int updated,
        int archived,
        int skipped,
        List<InboundSystemSyncResult> systems
    ) {
    }

    public record InboundSystemSyncResult(
        ExternalIntegrationSystem system,
        int received,
        int created,
        int updated,
        int archived,
        int skipped,
        List<InboundDatasetSyncResult> datasets
    ) {
    }

    public record InboundDatasetSyncResult(
        String dataset,
        String sourcePath,
        String mode,
        int received,
        int created,
        int updated,
        int archived,
        int skipped,
        String message,
        UUID integrationEventId
    ) {
    }
}