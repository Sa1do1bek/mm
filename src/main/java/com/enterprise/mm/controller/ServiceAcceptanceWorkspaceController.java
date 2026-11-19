package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ServiceAcceptanceActResponse;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.CommandResult;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.ActCreateCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.ActReviewCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.ClaimCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.IntegrationRetryCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.IntakeCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.MilestoneAcceptCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.PartialAcceptCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.PaymentCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.RemarkCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.VersionedActionCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.WorkActCreateCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.CaseListItem;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.QueueDescriptor;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Summary;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.ServiceRequestIntakeOptions;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.ServicesManagementDashboard;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Workspace;
import com.enterprise.mm.payload.ServiceRequestResponse;
import com.enterprise.mm.payload.ServiceRequestCreateRequest;
import com.enterprise.mm.payload.ServiceExecutionResponse;
import com.enterprise.mm.payload.ServiceClaimResponse;
import com.enterprise.mm.payload.WorkCompletionActResponse;
import com.enterprise.mm.service.ServiceAcceptanceWorkspaceCommandService.RemarkCommandResult;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.CaseFilter;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.OriginType;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.Scope;
import com.enterprise.mm.service.ServiceAcceptanceWorkspaceCommandService;
import com.enterprise.mm.service.ServiceAcceptanceWorkspaceService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/services-acceptance")
@RequiredArgsConstructor
public class ServiceAcceptanceWorkspaceController {
    private static final String READ_ROLES = "hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','SUPPLIER_MANAGER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')";

    private final ServiceAcceptanceWorkspaceService service;
    private final ServiceAcceptanceWorkspaceCommandService commandService;

    @GetMapping("/management")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<ServicesManagementDashboard> management(
        @RequestParam(defaultValue = "ALL") String scope,
        @RequestParam(required = false) String originType,
        @RequestParam(required = false) UUID ownerId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID contractId,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String exposureMetric,
        @RequestParam(required = false) String agingBucket,
        @RequestParam(required = false) LocalDate integrationDay,
        @RequestParam(required = false) Boolean hasOpenClaims,
        @RequestParam(required = false) Boolean hasRework,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) Boolean overdue,
        @RequestParam(required = false) String search
    ) {
        return ApiResponse.ok(service.management(
            filter("ALL", scope, originType, ownerId, supplierId, departmentId, contractId, currencyCode, category, exposureMetric,
                agingBucket, integrationDay, hasOpenClaims, hasRework, status, overdue, search)
        ));
    }

    @GetMapping("/summary")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<Summary> summary(
        @RequestParam(defaultValue = "ALL") String scope,
        @RequestParam(required = false) String originType,
        @RequestParam(required = false) UUID ownerId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID contractId,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String exposureMetric,
        @RequestParam(required = false) String agingBucket,
        @RequestParam(required = false) LocalDate integrationDay,
        @RequestParam(required = false) Boolean hasOpenClaims,
        @RequestParam(required = false) Boolean hasRework,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) Boolean overdue,
        @RequestParam(required = false) String search
    ) {
        return ApiResponse.ok(service.summary(filter(
            "ALL", scope, originType, ownerId, supplierId, departmentId, contractId, currencyCode, category, exposureMetric,
            agingBucket, integrationDay, hasOpenClaims, hasRework, status, overdue, search
        )));
    }

    @GetMapping("/queues")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<List<QueueDescriptor>> queues(
        @RequestParam(defaultValue = "ALL") String scope,
        @RequestParam(required = false) String originType,
        @RequestParam(required = false) UUID ownerId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID contractId,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String exposureMetric,
        @RequestParam(required = false) String agingBucket,
        @RequestParam(required = false) LocalDate integrationDay,
        @RequestParam(required = false) Boolean hasOpenClaims,
        @RequestParam(required = false) Boolean hasRework,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) Boolean overdue,
        @RequestParam(required = false) String search
    ) {
        return ApiResponse.ok(service.queues(filter(
            "ALL", scope, originType, ownerId, supplierId, departmentId, contractId, currencyCode, category, exposureMetric,
            agingBucket, integrationDay, hasOpenClaims, hasRework, status, overdue, search
        )));
    }

    @GetMapping("/cases")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<PageResponse<CaseListItem>> cases(
        @RequestParam(defaultValue = "EXECUTION") String queue,
        @RequestParam(defaultValue = "ALL") String scope,
        @RequestParam(required = false) String originType,
        @RequestParam(required = false) UUID ownerId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID contractId,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String exposureMetric,
        @RequestParam(required = false) String agingBucket,
        @RequestParam(required = false) LocalDate integrationDay,
        @RequestParam(required = false) Boolean hasOpenClaims,
        @RequestParam(required = false) Boolean hasRework,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) Boolean overdue,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "dueDate,asc") String sort,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, caseSort(sort));
        CaseFilter filter = filter(queue, scope, originType, ownerId, supplierId, departmentId, contractId, currencyCode, category, exposureMetric,
            agingBucket, integrationDay, hasOpenClaims, hasRework, status, overdue, search);
        return ApiResponse.ok(PageResponse.from(service.cases(filter, pageable)));
    }

    @GetMapping("/cases/{caseId}")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<Workspace> workspace(@PathVariable UUID caseId) {
        return ApiResponse.ok(service.workspace(caseId));
    }

    @GetMapping("/intake/options")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<ServiceRequestIntakeOptions> intakeOptions() {
        return ApiResponse.ok(service.intakeOptions());
    }

    @PostMapping("/requests")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<Workspace> createRequest(
        @Valid @RequestBody IntakeCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.createRequest(command, idempotencyKey));
    }

    @PostMapping("/requests/{requestId}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<ServiceRequestResponse> submitRequest(
        @PathVariable UUID requestId,
        @Valid @RequestBody VersionedActionCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.submitRequest(requestId, command, idempotencyKey));
    }

    @PostMapping("/requests/{requestId}/close")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','FINANCE_CONTROLLER','BUDGET_CONTROLLER')")
    public ApiResponse<ServiceRequestResponse> closeRequest(
        @PathVariable UUID requestId,
        @Valid @RequestBody VersionedActionCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.closeRequest(requestId, command, idempotencyKey));
    }

    @PostMapping("/requests/{requestId}/remarks")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<RemarkCommandResult> addRemark(
        @PathVariable UUID requestId,
        @Valid @RequestBody RemarkCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.addRemark(requestId, command, idempotencyKey));
    }

    @PostMapping("/executions/{executionId}/{transition}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceExecutionResponse> executionAction(
        @PathVariable UUID executionId,
        @PathVariable String transition,
        @Valid @RequestBody VersionedActionCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.executionAction(executionId, transition, command, idempotencyKey));
    }

    @PostMapping("/orders/{serviceOrderId}/acts")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceAcceptanceActResponse> createAct(
        @PathVariable UUID serviceOrderId,
        @Valid @RequestBody ActCreateCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.createAct(serviceOrderId, command, idempotencyKey));
    }

    @PostMapping("/acts/{actId}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceAcceptanceActResponse> submitAct(
        @PathVariable UUID actId,
        @Valid @RequestBody VersionedActionCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.submitAct(actId, command, idempotencyKey));
    }

    @PostMapping("/acts/{actId}/review")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceAcceptanceActResponse> reviewAct(
        @PathVariable UUID actId,
        @Valid @RequestBody ActReviewCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.reviewAct(actId, command, idempotencyKey));
    }

    @PostMapping("/acts/{actId}/rollback")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceAcceptanceActResponse> rollbackAct(
        @PathVariable UUID actId,
        @Valid @RequestBody VersionedActionCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.rollbackAct(actId, command, idempotencyKey));
    }

    @PostMapping("/cases/{caseId}/claims")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceClaimResponse> createClaim(
        @PathVariable UUID caseId,
        @Valid @RequestBody ClaimCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.createClaim(caseId, command, idempotencyKey));
    }

    @PostMapping("/claims/{claimId}/{transition}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST') or "
        + "(hasRole('SUPPLIER_MANAGER') and "
        + "(#transition.equalsIgnoreCase('start-resolution') or #transition.equalsIgnoreCase('start_resolution') "
        + "or #transition.equalsIgnoreCase('resolve') or #transition.equalsIgnoreCase('reject')))")
    public ApiResponse<ServiceClaimResponse> claimAction(
        @PathVariable UUID claimId,
        @PathVariable String transition,
        @Valid @RequestBody VersionedActionCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.claimAction(claimId, transition, command, idempotencyKey));
    }

    @PostMapping("/integrations/{eventId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','FINANCE_CONTROLLER')")
    public ApiResponse<CommandResult> retryIntegration(
        @PathVariable UUID eventId,
        @Valid @RequestBody IntegrationRetryCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.retryIntegration(eventId, command, idempotencyKey));
    }

    @PostMapping("/requests/{requestId}/partial-accept")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceRequestResponse> partialAccept(
        @PathVariable UUID requestId,
        @Valid @RequestBody PartialAcceptCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.partialAccept(requestId, command, idempotencyKey));
    }

    @PostMapping("/requests/{requestId}/milestones/{milestoneId}/accept")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<Workspace> acceptMilestone(
        @PathVariable UUID requestId,
        @PathVariable UUID milestoneId,
        @Valid @RequestBody MilestoneAcceptCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.acceptMilestone(requestId, milestoneId, command, idempotencyKey));
    }

    @PostMapping("/acts/{actId}/send-to-payment")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','BUDGET_CONTROLLER')")
    public ApiResponse<ServiceAcceptanceActResponse> sendToPayment(
        @PathVariable UUID actId,
        @Valid @RequestBody PaymentCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.sendToPayment(actId, command, idempotencyKey));
    }

    @PostMapping("/acts/{actId}/mark-paid")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER')")
    public ApiResponse<ServiceAcceptanceActResponse> markPaid(
        @PathVariable UUID actId,
        @Valid @RequestBody PaymentCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.markPaid(actId, command, idempotencyKey));
    }

    @PostMapping("/work-acts/{actId}/send-to-payment")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','BUDGET_CONTROLLER')")
    public ApiResponse<WorkCompletionActResponse> sendWorkActToPayment(
        @PathVariable UUID actId,
        @Valid @RequestBody PaymentCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.sendWorkActToPayment(actId, command, idempotencyKey));
    }

    @PostMapping("/work-items/{purchaseOrderItemId}/acts")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<WorkCompletionActResponse> createWorkAct(
        @PathVariable UUID purchaseOrderItemId,
        @Valid @RequestBody WorkActCreateCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.createWorkAct(purchaseOrderItemId, command, idempotencyKey));
    }

    @PostMapping("/work-acts/{actId}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<WorkCompletionActResponse> submitWorkAct(
        @PathVariable UUID actId,
        @Valid @RequestBody VersionedActionCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.submitWorkAct(actId, command, idempotencyKey));
    }

    @PostMapping("/work-acts/{actId}/mark-paid")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER')")
    public ApiResponse<WorkCompletionActResponse> markWorkActPaid(
        @PathVariable UUID actId,
        @Valid @RequestBody PaymentCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.ok(commandService.markWorkActPaid(actId, command, idempotencyKey));
    }

    private Sort caseSort(String value) {
        String[] parts = value == null ? new String[0] : value.split(",", 2);
        String field = parts.length == 0 || parts[0].isBlank() ? "dueDate" : parts[0].trim();
        String direction = parts.length < 2 ? "asc" : parts[1].trim();
        return PaginationUtils.sort(
            field,
            direction,
            Set.of(
                "code", "status", "phase", "supplierName", "responsibleName", "ownerName",
                "dueDate", "plannedEndDate", "progressPercent", "orderedAmount", "remainingAmount",
                "createdAt", "completedAt"
            ),
            Sort.by(Sort.Direction.ASC, "dueDate")
        );
    }

    private OriginType parseOriginType(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OriginType.valueOf(normalize(value));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Unsupported service-case origin type: " + value);
        }
    }

    private Scope parseScope(String value) {
        if (value == null || value.isBlank()) return Scope.ALL;
        try {
            return Scope.valueOf(normalize(value));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Unsupported service-case scope: " + value);
        }
    }

    private CaseFilter filter(
        String queue,
        String scope,
        String originType,
        UUID ownerId,
        UUID supplierId,
        List<String> status,
        Boolean overdue,
        String search
    ) {
        return filter(queue, scope, originType, ownerId, supplierId, null, null, null, null, null, null, null, null, null,
            status, overdue, search);
    }

    private CaseFilter filter(
        String queue,
        String scope,
        String originType,
        UUID ownerId,
        UUID supplierId,
        UUID departmentId,
        UUID contractId,
        String currencyCode,
        String category,
        String exposureMetric,
        String agingBucket,
        LocalDate integrationDay,
        Boolean hasOpenClaims,
        Boolean hasRework,
        List<String> status,
        Boolean overdue,
        String search
    ) {
        return new CaseFilter(
            queue, parseOriginType(originType), parseScope(scope), null, null,
            ownerId, supplierId, departmentId, contractId, currencyCode, category, exposureMetric, agingBucket, integrationDay, hasOpenClaims, hasRework,
            status, overdue, search
        );
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
