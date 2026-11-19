package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServiceRequestMilestoneApiContractTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void canonicalApiPersistsIntakeMilestonesAndExposesVersionedIdempotentAcceptance() throws Exception {
        String commands = Files.readString(ROOT.resolve("payload/ServiceAcceptanceWorkspaceCommandDtos.java"));
        String controller = Files.readString(ROOT.resolve("controller/ServiceAcceptanceWorkspaceController.java"));
        String commandService = Files.readString(ROOT.resolve("service/ServiceAcceptanceWorkspaceCommandService.java"));

        assertThat(commands).contains(
            "record MilestoneAcceptCommand",
            "Long expectedVersion",
            "BigDecimal acceptedVolume",
            "BigDecimal rejectedVolume",
            "boolean finalAcceptance"
        );
        assertThat(controller).contains(
            "@PostMapping(\"/requests/{requestId}/milestones/{milestoneId}/accept\")",
            "@RequestHeader(\"Idempotency-Key\") String idempotencyKey",
            "commandService.acceptMilestone(requestId, milestoneId, command, idempotencyKey)"
        );
        assertThat(commandService).contains(
            "milestoneService.createForRequest(created.getId(), command.milestones())",
            "SERVICE_REQUEST_MILESTONE_ACCEPT",
            "workspaceService.workspace(requestId)"
        );
        assertThat(commandService).doesNotContain("Milestone intake is not yet supported");
    }

    @Test
    void requestWorkspaceUsesPersistedMilestonesAndNeverSynthesizesOneForEmptyIntake() throws Exception {
        String workspaceService = Files.readString(ROOT.resolve("service/ServiceAcceptanceWorkspaceService.java"));

        assertThat(workspaceService).contains(
            "findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderBySequenceNumberAsc",
            "milestoneItem(",
            "ACCEPT_MILESTONE",
            "SERVICE_REQUEST_MILESTONE"
        );
        assertThat(workspaceService).doesNotContain(
            "processSteps(canonicalCase),\n            List.of(milestone(canonicalCase)),\n            List.of(),"
        );
    }
}
