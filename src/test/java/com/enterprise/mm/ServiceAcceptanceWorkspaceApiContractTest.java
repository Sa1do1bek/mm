package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServiceAcceptanceWorkspaceApiContractTest {
    private static final Path ROOT = Path.of("src/main/java/com/enterprise/mm");

    @Test
    void exposesCanonicalQueueDetailAndReplaySafeCommandRoutes() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/ServiceAcceptanceWorkspaceController.java"));
        assertThat(controller).contains("/api/workspaces/services-acceptance");
        assertThat(controller).contains("/summary", "/queues", "/cases", "/partial-accept", "/send-to-payment", "/mark-paid");
        assertThat(controller).contains("@RequestHeader(\"Idempotency-Key\")");
    }

    @Test
    void canonicalCasesExposeEveryServerFilterAndDatabaseSortContract() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/ServiceAcceptanceWorkspaceController.java"));
        assertThat(controller).contains(
            "String scope",
            "String originType",
            "UUID ownerId",
            "UUID supplierId",
            "UUID departmentId",
            "UUID contractId",
            "String currencyCode",
            "String category",
            "String agingBucket",
            "LocalDate integrationDay",
            "Boolean hasOpenClaims",
            "Boolean hasRework",
            "List<String> status",
            "Boolean overdue",
            "String search",
            "String sort"
        );
        assertThat(controller).contains("CaseFilter", "OriginType", "Scope");
        assertThat(controller).contains("service.cases(filter, pageable)");
    }

    @Test
    void workspaceContractCarriesEvidenceApprovalsBlockersIntegrationsAndAuditWithoutRawPayload() throws Exception {
        String dto = Files.readString(ROOT.resolve("payload/ServiceAcceptanceWorkspaceDtos.java"));
        assertThat(dto).contains("PaymentReadinessCheck", "ApprovalContext", "WorkspaceBlocker", "IntegrationImpactItem", "AuditTimelineItem");
        assertThat(dto).contains("List<ApprovalContext> requestApprovals");
        assertThat(dto).contains("UUID caseId", "String originType", "UUID originId", "UUID serviceOrderId", "UUID workCompletionActId");
        assertThat(dto).doesNotContain("String payload");
    }

    @Test
    void secondaryRegistriesExposeOnlyDraftSafeCrudWhileCanonicalFacadeOwnsLifecycleCommands() throws Exception {
        String serviceActs = Files.readString(ROOT.resolve("controller/ServiceAcceptanceActController.java"));
        String workActs = Files.readString(ROOT.resolve("controller/WorkCompletionActController.java"));
        String requests = Files.readString(ROOT.resolve("controller/ServiceRequestController.java"));
        String claims = Files.readString(ROOT.resolve("controller/ServiceClaimController.java"));
        String claimService = Files.readString(ROOT.resolve("service/ServiceClaimService.java"));

        assertThat(serviceActs).doesNotContain(
            "@PostMapping(\"/{id}/submit\")",
            "@PostMapping(\"/{id}/submit-for-approval\")",
            "@PostMapping(\"/{id}/rollback\")",
            "@PostMapping(\"/{id}/document-review/approve\")",
            "@PostMapping(\"/{id}/document-review/reject\")",
            "@PostMapping(\"/{id}/send-to-payment\")",
            "@PostMapping(\"/{id}/mark-paid\")"
        );
        assertThat(workActs).doesNotContain(
            "@PostMapping(\"/{id}/submit-for-approval\")",
            "@PostMapping(\"/{id}/send-to-payment\")",
            "@PostMapping(\"/{id}/mark-paid\")"
        );
        assertThat(requests).doesNotContain(
            "@PostMapping(\"/{id}/submit\")",
            "@PostMapping(\"/{id}/rollback\")",
            "@PostMapping(\"/{id}/close\")",
            "@PostMapping(\"/{id}/partial-accept\")",
            "@PostMapping(\"/{id}/submit-remarks\")"
        );
        assertThat(claims).doesNotContain(
            "@PostMapping(\"/{id}/send\")",
            "@PostMapping(\"/{id}/start-resolution\")",
            "@PostMapping(\"/{id}/resolve\")",
            "@PostMapping(\"/{id}/reject\")",
            "@PostMapping(\"/{id}/close\")"
        );
        assertThat(claimService).contains(
            "requireDraftClaim(entity, \"edited\")",
            "historyService.recordClaim(saved, \"UPDATED\", saved.getDescription())",
            "auditService.log(\"ServiceClaim\", saved.getId(), \"UPDATE\", saved.getCode(), null, null)"
        );
        assertThat(claimService).doesNotContain("ServiceClaimStatus.UNDER_RESOLUTION.name()).contains(entity.getStatus())");
    }

    @Test
    void everyAcceptanceActItemCarriesBackendOwnedEvidencePermissions() throws Exception {
        String dto = Files.readString(ROOT.resolve("payload/ServiceAcceptanceWorkspaceDtos.java"));
        String service = Files.readString(ROOT.resolve("service/ServiceAcceptanceWorkspaceService.java")).replace("\r\n", "\n");
        String acceptanceItem = dto.substring(
            dto.indexOf("public record AcceptanceActItem("),
            dto.indexOf("public record ClaimItem(")
        );
        String workItem = dto.substring(
            dto.indexOf("public record WorkCompletionActItem("),
            dto.indexOf("public record Workspace(")
        );

        assertThat(acceptanceItem).contains("EvidenceTarget evidenceTarget");
        assertThat(workItem).contains("EvidenceTarget evidenceTarget");
        assertThat(service).contains(
            "new AcceptanceActItem(actMapper.toResponse(act), evidence, evidenceTarget(",
            "new WorkCompletionActItem(\n            workActResponse(row), evidence, evidenceTarget("
        );
    }

    @Test
    void canonicalQueryRepositoryOwnsCountListAndDatabasePagingWithoutWorkspaceSnapshots() throws Exception {
        String contract = Files.readString(ROOT.resolve("repository/ServiceCaseWorkspaceQueryRepository.java"));
        String implementation = Files.readString(ROOT.resolve("repository/ServiceCaseWorkspaceQueryRepositoryImpl.java"));
        String service = Files.readString(ROOT.resolve("service/ServiceAcceptanceWorkspaceService.java"));
        assertThat(contract).contains(
            "Page<CaseRow> findCases", "long count", "Optional<CaseRow> findCase",
            "Optional<CaseRow> findCaseByAggregateId"
        );
        assertThat(implementation).contains("UNION ALL", "setFirstResult", "setMaxResults");
        assertThat(service).contains("caseQueryRepository.findCases(filter, pageable)");
    }

    @Test
    void intakeBindsTheExactGuidedPayloadAndDoesNotSilentlyDropMilestones() throws Exception {
        String commands = Files.readString(ROOT.resolve("payload/ServiceAcceptanceWorkspaceCommandDtos.java"));
        String dto = Files.readString(ROOT.resolve("payload/ServiceAcceptanceWorkspaceDtos.java"));
        String commandService = Files.readString(ROOT.resolve("service/ServiceAcceptanceWorkspaceCommandService.java"));
        String controller = Files.readString(ROOT.resolve("controller/ServiceAcceptanceWorkspaceController.java"));
        assertThat(commands).contains(
            "record IntakeCommand", "String scopeOfWork", "UUID responsibleId", "List<IntakeMilestone> milestones",
            "List<UUID> evidenceIds", "List<String> approvalPreview"
        );
        assertThat(commandService).contains(
            "setDescription(command.scopeOfWork())", "setResponsibleUserId(command.responsibleId())",
            "milestoneService.createForRequest(created.getId(), command.milestones())"
        );
        assertThat(commandService).doesNotContain("Milestone intake is not yet supported");
        assertThat(controller).contains("@RequestBody IntakeCommand command");
        assertThat(controller).contains("@GetMapping(\"/intake/options\")", "ServiceRequestIntakeOptions");
        assertThat(dto).contains("record IntakeOption", "record ServiceRequestIntakeOptions");
    }

    @Test
    void claimCommandRbacSeparatesBuyerCreationAndClosureFromSupplierResponse() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/ServiceAcceptanceWorkspaceController.java"));
        String service = Files.readString(ROOT.resolve("service/ServiceAcceptanceWorkspaceService.java"));
        assertThat(controller).contains(
            "@PostMapping(\"/cases/{caseId}/claims\")",
            "@PreAuthorize(\"hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')\")",
            "#transition.equalsIgnoreCase('start-resolution')",
            "#transition.equalsIgnoreCase('resolve')",
            "#transition.equalsIgnoreCase('reject')"
        );
        assertThat(controller).doesNotContain(
            "#transition.equalsIgnoreCase('send')",
            "#transition.equalsIgnoreCase('close')"
        );
        assertThat(service).contains(
            "else if (hasAnyRole(\"SUPPLIER_MANAGER\"))",
            "case \"SENT\" -> actions.add(action(\"START_CLAIM_RESOLUTION\"",
            "action(\"START_CLAIM_RESOLUTION\"",
            "action(\"RESOLVE_CLAIM\"",
            "action(\"REJECT_CLAIM\""
        );
    }

    @Test
    void createActContractIsExplicitlyStagedAndNeverAOneClickFullOrderAcceptance() throws Exception {
        String commands = Files.readString(ROOT.resolve("payload/ServiceAcceptanceWorkspaceCommandDtos.java"));
        String commandService = Files.readString(ROOT.resolve("service/ServiceAcceptanceWorkspaceCommandService.java"));
        assertThat(commands).contains(
            "record ActCreateCommand", "BigDecimal amount", "String stage", "LocalDate periodStart", "LocalDate periodEnd"
        );
        assertThat(commandService).contains(
            "Service act stage must be PARTIAL or FINAL", "command.amount(), stage, command.periodStart(), command.periodEnd()"
        );
    }

    @Test
    void managementDashboardUsesDatabaseAggregatesAndExplicitCurrencyAvailability() throws Exception {
        String controller = Files.readString(ROOT.resolve("controller/ServiceAcceptanceWorkspaceController.java"));
        String dto = Files.readString(ROOT.resolve("payload/ServiceAcceptanceWorkspaceDtos.java"));
        String repository = Files.readString(ROOT.resolve("repository/ServiceCaseWorkspaceQueryRepositoryImpl.java"));
        String service = Files.readString(ROOT.resolve("service/ServiceAcceptanceWorkspaceService.java"));
        assertThat(controller).contains("@GetMapping(\"/management\")", "ServicesManagementDashboard");
        assertThat(dto).contains("record ManagementSeriesItem", "record ServicesManagementDashboard", "List<MetricValue> metrics");
        assertThat(repository).contains("managementBuckets", "SUM(COALESCE(c.remaining_amount, 0))", "COUNT(DISTINCT");
        assertThat(service).contains("spans multiple currencies", "caseQueryRepository.managementBuckets(scoped)");
        assertThat(service).doesNotContain("Pageable.unpaged()");
    }
}
