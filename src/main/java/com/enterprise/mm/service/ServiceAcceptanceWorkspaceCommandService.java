package com.enterprise.mm.service;

import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.IntegrationHealthCommandDtos.OperatorCommand;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.CommandResult;
import java.math.BigDecimal;
import com.enterprise.mm.payload.ServiceAcceptanceActResponse;
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
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.payload.ServiceClaimCreateRequest;
import com.enterprise.mm.payload.ServiceClaimResponse;
import com.enterprise.mm.payload.ServiceExecutionResponse;
import com.enterprise.mm.payload.ServiceOrderResponse;
import com.enterprise.mm.payload.PurchaseOrderItemResponse;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.ServicePartialAcceptRequest;
import com.enterprise.mm.payload.ServiceRemarkResponse;
import com.enterprise.mm.payload.ServiceRemarksRequest;
import com.enterprise.mm.payload.ServiceRequestCreateRequest;
import com.enterprise.mm.payload.ServiceRequestResponse;
import com.enterprise.mm.payload.WorkCompletionActResponse;
import com.enterprise.mm.payload.WorkCompletionActCreateRequest;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@com.enterprise.mm.security.pilot.PilotCapabilityBoundary(value = com.enterprise.mm.security.pilot.PilotCapability.SERVICE_EXECUTION, reads = {})
public class ServiceAcceptanceWorkspaceCommandService {
    private final ServiceRequestService requestService;
    private final ServiceAcceptanceActService actService;
    private final ServiceExecutionService executionService;
    private final ServiceOrderService orderService;
    private final PurchaseOrderItemService purchaseOrderItemService;
    private final PurchaseOrderService purchaseOrderService;
    private final ServiceClaimService claimService;
    private final WorkCompletionActService workCompletionActService;
    private final IntegrationHealthCommandService integrationCommandService;
    private final ServiceAcceptanceWorkspaceService workspaceService;
    private final ServiceRequestMilestoneService milestoneService;
    private final IdempotencyService idempotencyService;

    public record RemarkCommandResult(List<ServiceRemarkResponse> remarks) {
        public RemarkCommandResult {
            remarks = remarks == null ? List.of() : List.copyOf(remarks);
        }
    }

    @Transactional
    public Workspace createRequest(IntakeCommand command, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        UUID commandId = UUID.nameUUIDFromBytes(
            ("SERVICE_REQUEST_CREATE:" + idempotencyKey.trim()).getBytes(StandardCharsets.UTF_8)
        );
        return execute(
            "SERVICE_REQUEST_CREATE", commandId, idempotencyKey, command, Workspace.class,
            () -> createRequestNow(command)
        );
    }

    @Transactional
    public ServiceRequestResponse submitRequest(UUID requestId, VersionedActionCommand command, String idempotencyKey) {
        return execute(
            "SERVICE_REQUEST_SUBMIT", requestId, idempotencyKey, command, ServiceRequestResponse.class,
            () -> {
                requireVersion(requestService.get(requestId).getVersion(), command.expectedVersion(), "Service request");
                return requestService.submit(requestId);
            }
        );
    }

    @Transactional
    public ServiceRequestResponse closeRequest(UUID requestId, VersionedActionCommand command, String idempotencyKey) {
        return execute(
            "SERVICE_REQUEST_CLOSE", requestId, idempotencyKey, command, ServiceRequestResponse.class,
            () -> {
                requireReason(command.reason(), "Closing a service request");
                requireVersion(requestService.get(requestId).getVersion(), command.expectedVersion(), "Service request");
                return requestService.close(requestId, action(command.reason()));
            }
        );
    }

    @Transactional
    public ServiceExecutionResponse executionAction(
        UUID executionId,
        String transition,
        VersionedActionCommand command,
        String idempotencyKey
    ) {
        String normalized = normalize(transition);
        return execute(
            "SERVICE_EXECUTION_" + normalized, executionId, idempotencyKey, command, ServiceExecutionResponse.class,
            () -> {
                if (Set.of("REJECT", "REWORK").contains(normalized)) {
                    requireReason(command.reason(), "Execution " + normalized.toLowerCase(java.util.Locale.ROOT));
                }
                requireVersion(executionService.get(executionId).getVersion(), command.expectedVersion(), "Service execution");
                return switch (normalized) {
                    case "START", "REWORK" -> executionService.start(executionId, action(command.reason()));
                    case "SUBMIT" -> executionService.submit(executionId, action(command.reason()));
                    case "ACCEPT" -> executionService.accept(executionId, action(command.reason()));
                    case "REJECT" -> executionService.reject(executionId, action(command.reason()));
                    default -> throw new BadRequestException("Unsupported service execution transition: " + transition);
                };
            }
        );
    }

    @Transactional
    public ServiceAcceptanceActResponse createAct(
        UUID serviceOrderId,
        ActCreateCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SERVICE_ACT_CREATE", serviceOrderId, idempotencyKey, command, ServiceAcceptanceActResponse.class,
            () -> {
                String stage = normalize(command.stage());
                if (!Set.of("PARTIAL", "FINAL").contains(stage)) {
                    throw new BadRequestException("Service act stage must be PARTIAL or FINAL");
                }
                if ((command.periodStart() == null) != (command.periodEnd() == null)) {
                    throw new BadRequestException("Both periodStart and periodEnd are required when an acceptance period is supplied");
                }
                if (command.periodStart() != null && command.periodStart().isAfter(command.periodEnd())) {
                    throw new BadRequestException("periodStart must be on or before periodEnd");
                }
                ServiceOrderResponse order = orderService.get(serviceOrderId);
                requireVersion(order.getVersion(), command.expectedVersion(), "Service order");
                return actService.accept(
                    serviceOrderId, command.acceptedBy().trim(), command.commissionNotes().trim(),
                    command.amount(), stage, command.periodStart(), command.periodEnd()
                );
            }
        );
    }

    @Transactional
    public ServiceAcceptanceActResponse submitAct(UUID actId, VersionedActionCommand command, String idempotencyKey) {
        return execute(
            "SERVICE_ACT_SUBMIT", actId, idempotencyKey, command, ServiceAcceptanceActResponse.class,
            () -> {
                requireVersion(actService.get(actId).getVersion(), command.expectedVersion(), "Service acceptance act");
                return actService.submit(actId);
            }
        );
    }

    @Transactional
    public ServiceAcceptanceActResponse reviewAct(UUID actId, ActReviewCommand command, String idempotencyKey) {
        String decision = normalize(command.decision());
        return execute(
            "SERVICE_ACT_REVIEW_" + decision, actId, idempotencyKey, command, ServiceAcceptanceActResponse.class,
            () -> {
                if ("REJECT".equals(decision)) requireReason(command.reason(), "Rejecting a service act");
                requireVersion(actService.get(actId).getVersion(), command.expectedVersion(), "Service acceptance act");
                return switch (decision) {
                    case "APPROVE" -> actService.documentApprove(actId, action(command.reason()));
                    case "REJECT" -> actService.documentReject(actId, action(command.reason()));
                    default -> throw new BadRequestException("Unsupported service-act review decision: " + command.decision());
                };
            }
        );
    }

    @Transactional
    public ServiceAcceptanceActResponse rollbackAct(UUID actId, VersionedActionCommand command, String idempotencyKey) {
        return execute(
            "SERVICE_ACT_ROLLBACK", actId, idempotencyKey, command, ServiceAcceptanceActResponse.class,
            () -> {
                requireReason(command.reason(), "Rolling back a service act");
                requireVersion(actService.get(actId).getVersion(), command.expectedVersion(), "Service acceptance act");
                return actService.rollback(actId, action(command.reason()));
            }
        );
    }

    @Transactional
    public RemarkCommandResult addRemark(UUID requestId, RemarkCommand command, String idempotencyKey) {
        return execute(
            "SERVICE_REQUEST_ADD_REMARK", requestId, idempotencyKey, command, RemarkCommandResult.class,
            () -> {
                requireVersion(requestService.get(requestId).getVersion(), command.expectedVersion(), "Service request");
                return new RemarkCommandResult(requestService.submitRemarks(
                    requestId,
                    new ServiceRemarksRequest(List.of(new ServiceRemarksRequest.Remark(
                        command.severity(), command.description(), command.dueDate(), command.assignedTo()
                    )))
                ));
            }
        );
    }

    @Transactional
    public ServiceClaimResponse createClaim(UUID caseId, ClaimCommand command, String idempotencyKey) {
        if (command.penaltyAmount() != null && command.penaltyAmount().signum() > 0
            && (command.currencyCode() == null || command.currencyCode().isBlank())) {
            throw new BadRequestException("currencyCode is required when penaltyAmount is positive");
        }
        return execute(
            "SERVICE_CASE_CREATE_CLAIM", caseId, idempotencyKey, command, ServiceClaimResponse.class,
            () -> createClaimNow(caseId, command)
        );
    }

    @Transactional
    public ServiceClaimResponse claimAction(
        UUID claimId,
        String transition,
        VersionedActionCommand command,
        String idempotencyKey
    ) {
        String normalized = normalize(transition);
        return execute(
            "SERVICE_CLAIM_" + normalized, claimId, idempotencyKey, command, ServiceClaimResponse.class,
            () -> {
                if (Set.of("START_RESOLUTION", "RESOLVE", "REJECT", "CLOSE").contains(normalized)) {
                    requireReason(command.reason(), "Claim " + normalized.toLowerCase(java.util.Locale.ROOT));
                }
                requireVersion(claimService.get(claimId).getVersion(), command.expectedVersion(), "Service claim");
                return switch (normalized) {
                    case "SEND" -> claimService.send(claimId, action(command.reason()));
                    case "START_RESOLUTION" -> claimService.startResolution(claimId, action(command.reason()));
                    case "RESOLVE" -> claimService.resolve(claimId, action(command.reason()));
                    case "REJECT" -> claimService.reject(claimId, action(command.reason()));
                    case "CLOSE" -> claimService.close(claimId, action(command.reason()));
                    default -> throw new BadRequestException("Unsupported service-claim transition: " + transition);
                };
            }
        );
    }

    @Transactional
    public CommandResult retryIntegration(UUID eventId, IntegrationRetryCommand command, String idempotencyKey) {
        return execute(
            "SERVICE_CASE_INTEGRATION_RETRY", eventId, idempotencyKey, command, CommandResult.class,
            () -> {
                workspaceService.requireAccessibleIntegrationEvent(eventId);
                return integrationCommandService.retry(
                    eventId, new OperatorCommand(command.expectedVersion(), command.reason().trim()), idempotencyKey
                );
            }
        );
    }

    @Transactional
    public ServiceRequestResponse partialAccept(UUID requestId, PartialAcceptCommand command, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        if (command.rejectedVolume().compareTo(BigDecimal.ZERO) > 0
            && (command.remarks() == null || command.remarks().isBlank())) {
            throw new BadRequestException("Remarks are required when service volume is rejected");
        }
        return idempotencyService.execute(
            "SERVICE_REQUEST_PARTIAL_ACCEPT",
            requestId,
            idempotencyKey,
            command,
            ServiceRequestResponse.class,
            () -> requestService.partialAccept(
                requestId,
                new ServicePartialAcceptRequest(command.acceptedVolume(), command.rejectedVolume(), command.remarks()),
                command.expectedVersion()
            )
        );
    }

    @Transactional
    public Workspace acceptMilestone(
        UUID requestId,
        UUID milestoneId,
        MilestoneAcceptCommand command,
        String idempotencyKey
    ) {
        Workspace accessible = workspaceService.workspace(requestId);
        if (accessible.request() == null || !Objects.equals(requestId, accessible.request().getId())) {
            throw new BadRequestException("Milestone acceptance requires a service-request case");
        }
        return execute(
            "SERVICE_REQUEST_MILESTONE_ACCEPT", milestoneId, idempotencyKey, command, Workspace.class,
            () -> {
                milestoneService.accept(
                    requestId,
                    milestoneId,
                    command.expectedVersion(),
                    new ServiceRequestMilestoneService.Acceptance(
                        command.acceptedVolume(), command.rejectedVolume(), command.finalAcceptance(), command.remarks()
                    )
                );
                return workspaceService.workspace(requestId);
            }
        );
    }

    @Transactional
    public ServiceAcceptanceActResponse sendToPayment(UUID actId, PaymentCommand command, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        return idempotencyService.execute(
            "SERVICE_ACT_SEND_TO_PAYMENT",
            actId,
            idempotencyKey,
            command,
            ServiceAcceptanceActResponse.class,
            () -> actService.sendToPayment(
                actId,
                trim(command.paymentReference()),
                trim(command.comment()),
                command.expectedVersion()
            )
        );
    }

    @Transactional
    public ServiceAcceptanceActResponse markPaid(UUID actId, PaymentCommand command, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        return idempotencyService.execute(
            "SERVICE_ACT_MARK_PAID",
            actId,
            idempotencyKey,
            command,
            ServiceAcceptanceActResponse.class,
            () -> actService.markPaid(
                actId,
                trim(command.paymentReference()),
                trim(command.comment()),
                command.expectedVersion()
            )
        );
    }

    @Transactional
    public WorkCompletionActResponse sendWorkActToPayment(
        UUID actId,
        PaymentCommand command,
        String idempotencyKey
    ) {
        return execute(
            "WORK_COMPLETION_ACT_SEND_TO_PAYMENT", actId, idempotencyKey, command, WorkCompletionActResponse.class,
            () -> workCompletionActService.sendToPayment(
                actId,
                trim(command.paymentReference()),
                trim(command.comment()),
                command.expectedVersion()
            )
        );
    }

    @Transactional
    public WorkCompletionActResponse createWorkAct(
        UUID purchaseOrderItemId,
        WorkActCreateCommand command,
        String idempotencyKey
    ) {
        return execute(
            "WORK_COMPLETION_ACT_CREATE", purchaseOrderItemId, idempotencyKey, command, WorkCompletionActResponse.class,
            () -> {
                PurchaseOrderItemResponse item = purchaseOrderItemService.get(purchaseOrderItemId);
                requireVersion(item.getVersion(), command.expectedVersion(), "Purchase order work line");
                PurchaseOrderResponse order = purchaseOrderService.get(item.getPurchaseOrderId());
                WorkCompletionActCreateRequest request = new WorkCompletionActCreateRequest();
                request.setName(command.name());
                request.setPurchaseOrderId(order.getId());
                request.setPurchaseOrderItemId(item.getId());
                request.setContractId(order.getContractId());
                request.setWorkLocation(command.workLocation());
                request.setMilestone(command.milestone());
                request.setPeriodStart(command.periodStart());
                request.setPeriodEnd(command.periodEnd());
                request.setCompletedQuantity(command.completedQuantity());
                request.setAmount(command.amount());
                request.setInspectionResult(command.inspectionResult());
                request.setAcceptedBy(command.acceptedBy());
                request.setCommissionNotes(command.commissionNotes());
                request.setRemarks(command.remarks());
                return workCompletionActService.create(request);
            }
        );
    }

    @Transactional
    public WorkCompletionActResponse submitWorkAct(
        UUID actId,
        VersionedActionCommand command,
        String idempotencyKey
    ) {
        return execute(
            "WORK_COMPLETION_ACT_SUBMIT", actId, idempotencyKey, command, WorkCompletionActResponse.class,
            () -> {
                requireVersion(workCompletionActService.get(actId).getVersion(), command.expectedVersion(), "Work completion act");
                return workCompletionActService.submitForApproval(actId);
            }
        );
    }

    @Transactional
    public WorkCompletionActResponse markWorkActPaid(
        UUID actId,
        PaymentCommand command,
        String idempotencyKey
    ) {
        return execute(
            "WORK_COMPLETION_ACT_MARK_PAID", actId, idempotencyKey, command, WorkCompletionActResponse.class,
            () -> workCompletionActService.markPaid(
                actId,
                trim(command.paymentReference()),
                trim(command.comment()),
                command.expectedVersion()
            )
        );
    }

    private void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for service acceptance commands");
        }
    }

    private ServiceClaimResponse createClaimNow(UUID caseId, ClaimCommand command) {
        var workspace = workspaceService.workspace(caseId);
        requireVersion(workspace.version(), command.expectedVersion(), "Service case");
        ServiceClaimCreateRequest request = new ServiceClaimCreateRequest();
        request.setName("Service acceptance claim");
        request.setDescription(command.description());
        switch (normalize(workspace.originType())) {
            case "SERVICE_REQUEST" -> {
                if (workspace.request() == null) {
                    throw new BadRequestException("SERVICE_REQUEST workspace is missing its canonical request origin");
                }
                request.setServiceRequestId(workspace.request().getId());
                request.setServiceActId(workspace.acts().isEmpty() ? null : workspace.acts().get(0).act().getId());
            }
            case "PO_SERVICE" -> {
                request.setServiceOrderId(workspace.originId());
                request.setServiceActId(workspace.acts().isEmpty() ? null : workspace.acts().get(0).act().getId());
            }
            case "PO_WORKS" -> {
                request.setPurchaseOrderItemId(workspace.originId());
                request.setWorkCompletionActId(workspace.workActs().isEmpty()
                    ? null : workspace.workActs().get(0).act().getId());
            }
            default -> throw new BadRequestException("Unsupported service claim origin: " + workspace.originType());
        }
        request.setClaimType(command.claimType());
        request.setSeverity(command.severity());
        request.setAssignedTo(command.assignedTo());
        request.setDeadline(command.deadline());
        request.setPenaltyAmount(command.penaltyAmount());
        request.setCurrencyCode(command.currencyCode());
        return claimService.create(request);
    }

    private Workspace createRequestNow(IntakeCommand command) {
        if (!command.evidenceIds().isEmpty()) {
            throw new BadRequestException("Pre-linked evidenceIds are not supported; upload evidence to the returned evidenceTarget");
        }
        if (!command.approvalPreview().isEmpty()) {
            throw new BadRequestException("approvalPreview is server-owned and cannot be submitted");
        }
        ServiceRequestCreateRequest request = new ServiceRequestCreateRequest();
        request.setName(command.title());
        request.setTitle(command.title());
        request.setDescription(command.scopeOfWork());
        request.setCategory(command.category());
        request.setSupplierId(command.supplierId());
        request.setDepartmentId(command.departmentId());
        request.setResponsibleUserId(command.responsibleId());
        request.setPlannedStartDate(command.plannedStartDate());
        request.setPlannedEndDate(command.plannedEndDate());
        request.setAcceptanceMode(normalize(command.acceptanceMode()));
        request.setOrderedQuantity(command.orderedQuantity());
        request.setUomCode(trim(command.uomCode()));
        request.setOrderedAmount(command.orderedAmount());
        request.setEstimatedAmount(command.orderedAmount());
        request.setCurrencyCode(command.currencyCode() == null ? null : normalize(command.currencyCode()));
        ServiceRequestResponse created = requestService.create(request);
        milestoneService.createForRequest(created.getId(), command.milestones());
        return workspaceService.workspace(created.getId());
    }

    private void requireVersion(Long actualVersion, Long expectedVersion, String label) {
        if (expectedVersion == null) {
            throw new BadRequestException("expectedVersion is required for " + label.toLowerCase());
        }
        if (!Objects.equals(actualVersion, expectedVersion)) {
            throw new ConflictException(label + " was changed by another user; refresh the workspace and retry");
        }
    }

    private <T> T execute(
        String operation,
        UUID resourceId,
        String idempotencyKey,
        Object command,
        Class<T> responseType,
        Supplier<T> action
    ) {
        requireIdempotencyKey(idempotencyKey);
        return idempotencyService.execute(
            operation, resourceId, idempotencyKey, command, responseType, action
        );
    }

    private ServiceActionRequest action(String reason) {
        return new ServiceActionRequest(trim(reason), null, null);
    }

    private String requireReason(String value, String action) {
        String normalized = trim(value);
        if (normalized == null) throw new BadRequestException(action + " requires a reason");
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
