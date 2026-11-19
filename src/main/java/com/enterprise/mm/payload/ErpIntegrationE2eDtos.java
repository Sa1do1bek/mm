package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class ErpIntegrationE2eDtos {
    private ErpIntegrationE2eDtos() {
    }

    public record ErpIntegrationE2eRequest(
        List<String> suites,
        List<String> systems,
        Boolean requireLive,
        Boolean dispatchOutbound,
        Boolean syncInbound,
        Boolean archiveOriginals,
        Integer limit
    ) {
    }

    public record ErpIntegrationE2eReport(
        LocalDateTime checkedAt,
        String status,
        boolean liveReady,
        int suitesProcessed,
        int systemsProcessed,
        int outboundPublished,
        int inboundReceived,
        int blockers,
        List<ErpIntegrationE2eSuiteResult> suites
    ) {
    }

    public record ErpIntegrationE2eSuiteResult(
        String suite,
        String name,
        String status,
        int systemsProcessed,
        int outboundPublished,
        int inboundReceived,
        int blockers,
        List<ErpIntegrationE2eSystemResult> systems
    ) {
    }

    public record ErpIntegrationE2eSystemResult(
        String suite,
        String system,
        String name,
        String status,
        String mode,
        String baseUrl,
        String healthStatus,
        String healthMessage,
        String outboundEventType,
        UUID outboundEventId,
        String outboundStatus,
        String outboundLastError,
        int inboundReceived,
        int inboundCreated,
        int inboundUpdated,
        int inboundSkipped,
        String inboundMode,
        String inboundMessage,
        List<String> actions
    ) {
    }
}