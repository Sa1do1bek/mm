package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InventoryPeriodCloseWorkspaceApiContractTest {
    @Test
    void exposesConsolidatedWorkspaceAndVersionedCloseCommands() throws Exception {
        String controller = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/controller/inventoryfinance/InventoryPeriodCloseController.java"
        ));
        String commands = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/payload/InventoryPeriodCloseCommandDtos.java"
        ));
        String workspace = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/payload/InventoryPeriodCloseWorkspaceDtos.java"
        ));

        assertThat(controller).contains(
            "@GetMapping(\"/workspace/summary\")",
            "@GetMapping(\"/{id}/workspace\")",
            "VersionedCommand request",
            "ReopenRequest request"
        );
        assertThat(commands).contains("Long expectedVersion", "@NotBlank", "String reason");
        assertThat(workspace).contains(
            "record ReadinessCheck",
            "record CloseAuthorization",
            "record ValuationJobItem",
            "record IntegrationImpactItem",
            "record AuditTimelineItem",
            "List<WorkspaceAction> allowedActions"
        );
    }

    @Test
    void reopenPolicyRequiresReasonAndEmitsAuditableIntegrationImpact() throws Exception {
        String service = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/service/InventoryPeriodCloseService.java"
        ));
        String security = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/security/SecurityConfig.java"
        ));

        assertThat(service).contains(
            "requireVersion(entity, expectedVersion)",
            "reason.trim().length() < 5",
            "INVENTORY.PERIOD_REOPENED",
            "reason=\" + reason.trim()"
        );
        assertThat(security).contains(
            "\"/api/inventory-finance/period-closes/*/reopen\"",
            "\"MM_MANAGER\""
        );
    }
}
