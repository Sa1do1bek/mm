package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IntegrationHealthWorkspaceApiContractTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void exposesCanonicalQueuesDetailAndVersionedReplaySafeOperatorCommands() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/IntegrationHealthWorkspaceController.java"));
        String commands = Files.readString(ROOT.resolve("payload/IntegrationHealthCommandDtos.java"));

        assertThat(controller).contains("/api/workspaces/integration-health");
        assertThat(controller).contains("/summary", "/queues", "/events", "/retry", "/replay", "/cancel");
        assertThat(controller).contains("@RequestHeader(\"Idempotency-Key\")", "@PreAuthorize(WRITE_ROLES)");
        assertThat(commands).contains("Long expectedVersion", "@NotBlank", "String reason");
    }

    @Test
    void detailPrioritizesBusinessImpactAndOnlyCarriesRedactedTechnicalMetadata() throws Exception {
        String dto = Files.readString(ROOT.resolve("payload/IntegrationHealthWorkspaceDtos.java"));
        String redactor = Files.readString(ROOT.resolve("service/IntegrationPayloadRedactor.java"));
        String repository = Files.readString(ROOT.resolve("repository/IntegrationEventRepository.java"));

        assertThat(dto).contains(
            "BusinessDocumentReference",
            "String businessImpact",
            "String owner",
            "String runbookCode",
            "JsonNode redactedRequestMetadata",
            "JsonNode responseMetadata",
            "List<AttemptTimelineItem> attempts",
            "List<AuditTimelineItem> auditTimeline"
        );
        assertThat(redactor).contains("authorization", "clientsecret", "apikey", "bearertoken", "signature", "credential");
        assertThat(repository).contains("Page<IntegrationEvent> searchWorkspace", "DEAD_MANUAL_DECISION", "AWAITING_RECONCILIATION");
    }
}
