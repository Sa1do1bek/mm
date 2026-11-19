package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.IntegrationHealthWorkspaceDtos.CommandResult;
import com.enterprise.mm.payload.ServiceAcceptanceActResponse;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.ActCreateCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.ClaimCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.IntegrationRetryCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.IntakeCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.IntakeMilestone;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.MilestoneAcceptCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.PartialAcceptCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.PaymentCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.RemarkCommand;
import com.enterprise.mm.payload.ServiceAcceptanceWorkspaceCommandDtos.VersionedActionCommand;
import com.enterprise.mm.payload.ServicePartialAcceptRequest;
import com.enterprise.mm.payload.ServiceClaimCreateRequest;
import com.enterprise.mm.payload.ServiceClaimResponse;
import com.enterprise.mm.payload.ServiceRemarkResponse;
import com.enterprise.mm.payload.ServiceRemarksRequest;
import com.enterprise.mm.payload.ServiceRequestResponse;
import com.enterprise.mm.payload.ServiceOrderResponse;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.WorkCompletionActResponse;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceAcceptanceWorkspaceCommandServiceTest {
    @Mock private ServiceRequestService requestService;
    @Mock private ServiceAcceptanceActService actService;
    @Mock private ServiceExecutionService executionService;
    @Mock private ServiceOrderService orderService;
    @Mock private PurchaseOrderItemService purchaseOrderItemService;
    @Mock private PurchaseOrderService purchaseOrderService;
    @Mock private ServiceClaimService claimService;
    @Mock private WorkCompletionActService workCompletionActService;
    @Mock private IntegrationHealthCommandService integrationCommandService;
    @Mock private ServiceAcceptanceWorkspaceService workspaceService;
    @Mock private ServiceRequestMilestoneService milestoneService;
    @Mock private ServiceRequestRepository requestRepository;
    @Mock private ServiceExecutionRepository executionRepository;
    @Mock private ServiceAcceptanceActRepository actRepository;
    @Mock private ServiceClaimRepository claimRepository;
    @Mock private ServiceOrderRepository orderRepository;
    @Mock private WorkCompletionActRepository workCompletionActRepository;
    @Mock private IdempotencyService idempotencyService;
    @InjectMocks private ServiceAcceptanceWorkspaceCommandService service;

    @Test
    void guidedIntakePersistsValidatedMilestonesBeforeReturningTheWorkspace() {
        UUID requestId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID responsibleId = UUID.randomUUID();
        List<IntakeMilestone> milestones = List.of(
            new IntakeMilestone("Stage one", LocalDate.now().plusDays(5), null, BigDecimal.valueOf(40)),
            new IntakeMilestone("Stage two", LocalDate.now().plusDays(10), null, BigDecimal.valueOf(60))
        );
        IntakeCommand command = new IntakeCommand(
            "Pump maintenance", "MAINTENANCE", "Inspect and overhaul the pump", null,
            departmentId, responsibleId, LocalDate.now(), LocalDate.now().plusDays(10),
            "AMOUNT", null, null, BigDecimal.valueOf(100), "UZS", milestones, List.of(), List.of()
        );
        ServiceRequestResponse created = new ServiceRequestResponse();
        created.setId(requestId);
        created.setStatus("DRAFT");
        var workspace = new com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Workspace(
            created, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
        when(idempotencyService.execute(
            eq("SERVICE_REQUEST_CREATE"), any(UUID.class), eq("intake-key"), eq(command),
            eq(com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Workspace.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(requestService.create(any(com.enterprise.mm.payload.ServiceRequestCreateRequest.class))).thenReturn(created);
        when(workspaceService.workspace(requestId)).thenReturn(workspace);

        assertThat(service.createRequest(command, "intake-key")).isSameAs(workspace);

        ArgumentCaptor<com.enterprise.mm.payload.ServiceRequestCreateRequest> requestCaptor =
            ArgumentCaptor.forClass(com.enterprise.mm.payload.ServiceRequestCreateRequest.class);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(requestService, milestoneService, workspaceService);
        order.verify(requestService).create(requestCaptor.capture());
        order.verify(milestoneService).createForRequest(requestId, milestones);
        order.verify(workspaceService).workspace(requestId);
        assertThat(requestCaptor.getValue().getDescription()).isEqualTo("Inspect and overhaul the pump");
        assertThat(requestCaptor.getValue().getResponsibleUserId()).isEqualTo(responsibleId);
        assertThat(requestCaptor.getValue().getOrderedAmount()).isEqualByComparingTo("100");
    }

    @Test
    void structuredClaimPreservesRequiredOwnerInsideIdempotencyBoundary() {
        UUID caseId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID assignedTo = UUID.randomUUID();
        ClaimCommand command = new ClaimCommand(
            6L, "QUALITY", "HIGH", "Acceptance evidence is incomplete",
            LocalDate.now().plusDays(3), assignedTo, BigDecimal.valueOf(250), "UZS"
        );
        ServiceRequestResponse request = new ServiceRequestResponse();
        request.setId(requestId);
        request.setCode("SR-CLAIM-1");
        request.setSupplierId(supplierId);
        request.setStatus("WAITING_ACCEPTANCE");
        request.setVersion(6L);
        var workspace = new com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Workspace(
            request, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
        ServiceClaimResponse created = new ServiceClaimResponse();
        when(workspaceService.workspace(caseId)).thenReturn(workspace);
        when(idempotencyService.execute(
            eq("SERVICE_CASE_CREATE_CLAIM"), eq(caseId), eq("claim-key"), eq(command),
            eq(ServiceClaimResponse.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(claimService.create(any(ServiceClaimCreateRequest.class))).thenReturn(created);

        assertThat(service.createClaim(caseId, command, "claim-key")).isSameAs(created);

        ArgumentCaptor<ServiceClaimCreateRequest> requestCaptor = ArgumentCaptor.forClass(ServiceClaimCreateRequest.class);
        verify(claimService).create(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getAssignedTo()).isEqualTo(assignedTo);
        assertThat(requestCaptor.getValue().getServiceRequestId()).isEqualTo(requestId);
        assertThat(requestCaptor.getValue().getSupplierId()).isNull();
    }

    @Test
    void requestlessPoServiceClaimUsesServerOwnedOrderOriginWithoutClientSupplier() {
        UUID caseId = UUID.randomUUID();
        ClaimCommand command = new ClaimCommand(
            3L, "QUALITY", "HIGH", "Act quality mismatch",
            LocalDate.now().plusDays(2), UUID.randomUUID(), BigDecimal.ZERO, "UZS"
        );
        var workspace = originWorkspace(caseId, "PO_SERVICE", "SO-CLAIM", 3L);
        when(idempotencyService.execute(
            eq("SERVICE_CASE_CREATE_CLAIM"), eq(caseId), eq("po-service-claim"), eq(command),
            eq(ServiceClaimResponse.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(workspaceService.workspace(caseId)).thenReturn(workspace);
        when(claimService.create(any(ServiceClaimCreateRequest.class))).thenReturn(new ServiceClaimResponse());

        service.createClaim(caseId, command, "po-service-claim");

        ArgumentCaptor<ServiceClaimCreateRequest> captor = ArgumentCaptor.forClass(ServiceClaimCreateRequest.class);
        verify(claimService).create(captor.capture());
        assertThat(captor.getValue().getServiceOrderId()).isEqualTo(caseId);
        assertThat(captor.getValue().getServiceRequestId()).isNull();
        assertThat(captor.getValue().getSupplierId()).isNull();
    }

    @Test
    void poWorksClaimUsesServerOwnedLineOrigin() {
        UUID lineId = UUID.randomUUID();
        ClaimCommand command = new ClaimCommand(
            5L, "DEFECT", "CRITICAL", "Inspection failed",
            LocalDate.now().plusDays(1), UUID.randomUUID(), BigDecimal.valueOf(50), "UZS"
        );
        var workspace = originWorkspace(lineId, "PO_WORKS", "WORK-LINE-CLAIM", 5L);
        when(idempotencyService.execute(
            eq("SERVICE_CASE_CREATE_CLAIM"), eq(lineId), eq("po-works-claim"), eq(command),
            eq(ServiceClaimResponse.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(workspaceService.workspace(lineId)).thenReturn(workspace);
        when(claimService.create(any(ServiceClaimCreateRequest.class))).thenReturn(new ServiceClaimResponse());

        service.createClaim(lineId, command, "po-works-claim");

        ArgumentCaptor<ServiceClaimCreateRequest> captor = ArgumentCaptor.forClass(ServiceClaimCreateRequest.class);
        verify(claimService).create(captor.capture());
        assertThat(captor.getValue().getPurchaseOrderItemId()).isEqualTo(lineId);
        assertThat(captor.getValue().getServiceOrderId()).isNull();
        assertThat(captor.getValue().getSupplierId()).isNull();
    }

    @Test
    void partialAcceptanceUsesVersionAndIdempotencyBoundary() {
        UUID requestId = UUID.randomUUID();
        PartialAcceptCommand command = new PartialAcceptCommand(4L, BigDecimal.TEN, BigDecimal.ZERO, "Stage one accepted");
        when(idempotencyService.execute(eq("SERVICE_REQUEST_PARTIAL_ACCEPT"), eq(requestId), eq("key-1"), eq(command), eq(ServiceRequestResponse.class), any()))
            .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(requestService.partialAccept(eq(requestId), any(ServicePartialAcceptRequest.class), eq(4L))).thenReturn(new ServiceRequestResponse());

        service.partialAccept(requestId, command, "key-1");

        verify(requestService).partialAccept(requestId, new ServicePartialAcceptRequest(BigDecimal.TEN, BigDecimal.ZERO, "Stage one accepted"), 4L);
    }

    @Test
    void milestoneAcceptanceUsesCanonicalAccessAndMilestoneVersionInsideReplayBoundary() {
        UUID requestId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        MilestoneAcceptCommand command = new MilestoneAcceptCommand(
            5L, BigDecimal.valueOf(25), BigDecimal.ZERO, false, "Stage checked"
        );
        ServiceRequestResponse request = new ServiceRequestResponse();
        request.setId(requestId);
        request.setStatus("WAITING_ACCEPTANCE");
        var workspace = new com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Workspace(
            request, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
        when(workspaceService.workspace(requestId)).thenReturn(workspace);
        when(idempotencyService.execute(
            eq("SERVICE_REQUEST_MILESTONE_ACCEPT"), eq(milestoneId), eq("milestone-key"), eq(command),
            eq(com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Workspace.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());

        assertThat(service.acceptMilestone(requestId, milestoneId, command, "milestone-key")).isSameAs(workspace);

        verify(workspaceService, org.mockito.Mockito.times(2)).workspace(requestId);
        verify(milestoneService).accept(
            eq(requestId), eq(milestoneId), eq(5L),
            eq(new ServiceRequestMilestoneService.Acceptance(
                BigDecimal.valueOf(25), BigDecimal.ZERO, false, "Stage checked"
            ))
        );
    }

    @Test
    void stagedActCreationForwardsAmountStageAndPeriodInsideReplayBoundary() {
        UUID orderId = UUID.randomUUID();
        LocalDate periodStart = LocalDate.now().minusDays(7);
        LocalDate periodEnd = LocalDate.now();
        ActCreateCommand command = new ActCreateCommand(
            3L, "Commission chair", "Stage one evidence checked", BigDecimal.valueOf(275),
            "partial", periodStart, periodEnd
        );
        ServiceOrderResponse order = new ServiceOrderResponse();
        order.setId(orderId);
        order.setVersion(3L);
        ServiceAcceptanceActResponse created = new ServiceAcceptanceActResponse();
        when(orderService.get(orderId)).thenReturn(order);
        when(idempotencyService.execute(
            eq("SERVICE_ACT_CREATE"), eq(orderId), eq("act-create-key"), eq(command),
            eq(ServiceAcceptanceActResponse.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(actService.accept(
            orderId, "Commission chair", "Stage one evidence checked", BigDecimal.valueOf(275),
            "PARTIAL", periodStart, periodEnd
        )).thenReturn(created);

        assertThat(service.createAct(orderId, command, "act-create-key")).isSameAs(created);
        verify(actService).accept(
            orderId, "Commission chair", "Stage one evidence checked", BigDecimal.valueOf(275),
            "PARTIAL", periodStart, periodEnd
        );
    }

    @Test
    void stagedActRejectsInvalidStageAndIncompletePeriodBeforeDomainMutation() {
        UUID orderId = UUID.randomUUID();
        ActCreateCommand invalidStage = new ActCreateCommand(
            1L, "Chair", "Reviewed", BigDecimal.TEN, "FULL", null, null
        );
        ActCreateCommand incompletePeriod = new ActCreateCommand(
            1L, "Chair", "Reviewed", BigDecimal.TEN, "PARTIAL", LocalDate.now(), null
        );
        when(idempotencyService.execute(
            eq("SERVICE_ACT_CREATE"), eq(orderId), any(), any(), eq(ServiceAcceptanceActResponse.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());

        assertThatThrownBy(() -> service.createAct(orderId, invalidStage, "bad-stage"))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("PARTIAL or FINAL");
        assertThatThrownBy(() -> service.createAct(orderId, incompletePeriod, "bad-period"))
            .isInstanceOf(BadRequestException.class).hasMessageContaining("Both periodStart and periodEnd");
        verify(actService, never()).accept(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectedVolumeRequiresReasonAndEveryCriticalCommandRequiresIdempotencyKey() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.partialAccept(id, new PartialAcceptCommand(1L, BigDecimal.ZERO, BigDecimal.ONE, " "), "key"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Remarks");
        assertThatThrownBy(() -> service.sendToPayment(id, new PaymentCommand(2L, "PAY-1", "ready"), " "))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void paymentCommandForwardsExpectedVersionInsideReplayBoundary() {
        UUID actId = UUID.randomUUID();
        PaymentCommand command = new PaymentCommand(7L, " PAY-7 ", " approved ");
        when(idempotencyService.execute(eq("SERVICE_ACT_SEND_TO_PAYMENT"), eq(actId), eq("key-7"), eq(command), eq(ServiceAcceptanceActResponse.class), any()))
            .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(actService.sendToPayment(actId, "PAY-7", "approved", 7L)).thenReturn(new ServiceAcceptanceActResponse());

        service.sendToPayment(actId, command, "key-7");

        verify(actService).sendToPayment(actId, "PAY-7", "approved", 7L);
    }

    @Test
    void paymentConfirmationUsesTheSameReplayAndVersionBoundary() {
        UUID actId = UUID.randomUUID();
        PaymentCommand command = new PaymentCommand(9L, "PAY-9", "bank confirmed");
        when(idempotencyService.execute(eq("SERVICE_ACT_MARK_PAID"), eq(actId), eq("key-9"), eq(command), eq(ServiceAcceptanceActResponse.class), any()))
            .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(actService.markPaid(actId, "PAY-9", "bank confirmed", 9L)).thenReturn(new ServiceAcceptanceActResponse());

        service.markPaid(actId, command, "key-9");

        verify(actService).markPaid(actId, "PAY-9", "bank confirmed", 9L);
    }

    @Test
    void structuredRemarkChecksVersionAndReplaysAsOneCommand() {
        UUID requestId = UUID.randomUUID();
        RemarkCommand command = new RemarkCommand(
            3L, "HIGH", "Replace damaged insulation", LocalDate.now().plusDays(2), UUID.randomUUID()
        );
        ServiceRequestResponse request = new ServiceRequestResponse();
        request.setId(requestId);
        request.setVersion(3L);
        when(requestService.get(requestId)).thenReturn(request);
        ServiceRemarkResponse remark = new ServiceRemarkResponse(
            UUID.randomUUID(), "HIGH", command.description(), command.dueDate(), command.assignedTo(), "OPEN", null
        );
        when(idempotencyService.execute(
            eq("SERVICE_REQUEST_ADD_REMARK"), eq(requestId), eq("remark-key"), eq(command),
            eq(ServiceAcceptanceWorkspaceCommandService.RemarkCommandResult.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(requestService.submitRemarks(eq(requestId), any(ServiceRemarksRequest.class))).thenReturn(List.of(remark));

        var result = service.addRemark(requestId, command, "remark-key");

        assertThat(result.remarks()).containsExactly(remark);
        verify(requestService).submitRemarks(
            requestId,
            new ServiceRemarksRequest(List.of(new ServiceRemarksRequest.Remark(
                command.severity(), command.description(), command.dueDate(), command.assignedTo()
            )))
        );
    }

    @Test
    void staleVersionIsAConflictBeforeUnsafeRequestTransition() {
        UUID requestId = UUID.randomUUID();
        ServiceRequestResponse request = new ServiceRequestResponse();
        request.setId(requestId);
        request.setVersion(8L);
        when(requestService.get(requestId)).thenReturn(request);
        when(idempotencyService.execute(
            eq("SERVICE_REQUEST_SUBMIT"), eq(requestId), eq("submit-key"), any(),
            eq(ServiceRequestResponse.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());

        assertThatThrownBy(() -> service.submitRequest(
            requestId, new VersionedActionCommand(7L, "ready"), "submit-key"
        )).isInstanceOf(ConflictException.class).hasMessageContaining("refresh");
    }

    @Test
    void integrationRetryDelegatesRequiredVersionReasonAndIdempotency() {
        UUID eventId = UUID.randomUUID();
        IntegrationRetryCommand command = new IntegrationRetryCommand(5L, "Transport restored");
        CommandResult response = new CommandResult(eventId, "PUBLISHED", 6L, null, "retried", "PUBLISHED", "SYSTEM");
        when(idempotencyService.execute(
            eq("SERVICE_CASE_INTEGRATION_RETRY"), eq(eventId), eq("retry-key"), eq(command),
            eq(CommandResult.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(integrationCommandService.retry(
            eventId,
            new com.enterprise.mm.payload.IntegrationHealthCommandDtos.OperatorCommand(5L, "Transport restored"),
            "retry-key"
        )).thenReturn(response);

        assertThat(service.retryIntegration(eventId, command, "retry-key")).isEqualTo(response);
        verify(workspaceService).requireAccessibleIntegrationEvent(eventId);
    }

    @Test
    void integrationRetryFailsClosedWhenEventIsNotLinkedToAccessibleServiceCase() {
        UUID eventId = UUID.randomUUID();
        IntegrationRetryCommand command = new IntegrationRetryCommand(2L, "Retry after transport recovery");
        when(idempotencyService.execute(
            eq("SERVICE_CASE_INTEGRATION_RETRY"), eq(eventId), eq("denied-key"), eq(command),
            eq(CommandResult.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        org.mockito.Mockito.doThrow(new com.enterprise.mm.error.NotFoundException("Integration event not found"))
            .when(workspaceService).requireAccessibleIntegrationEvent(eventId);

        assertThatThrownBy(() -> service.retryIntegration(eventId, command, "denied-key"))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
        verify(integrationCommandService, never()).retry(any(), any(), any());
    }

    @Test
    void workActPaymentUsesExpectedVersionAndReplayBoundary() {
        UUID actId = UUID.randomUUID();
        PaymentCommand command = new PaymentCommand(4L, " TREASURY-CLIENT-4 ", " accepted ");
        WorkCompletionActResponse response = new WorkCompletionActResponse();
        response.setId(actId);
        response.setPaymentReference("TREASURY-SERVER-4");
        when(idempotencyService.execute(
            eq("WORK_COMPLETION_ACT_SEND_TO_PAYMENT"), eq(actId), eq("work-pay-key"), eq(command),
            eq(WorkCompletionActResponse.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(workCompletionActService.sendToPayment(actId, "TREASURY-CLIENT-4", "accepted", 4L)).thenReturn(response);

        assertThat(service.sendWorkActToPayment(actId, command, "work-pay-key")).isSameAs(response);
        verify(workCompletionActService).sendToPayment(actId, "TREASURY-CLIENT-4", "accepted", 4L);
        verify(workCompletionActService, never()).get(actId);
    }

    @Test
    void workActPaymentAllowsMissingClientPaymentReference() {
        UUID actId = UUID.randomUUID();
        WorkCompletionActResponse saved = new WorkCompletionActResponse();
        saved.setId(actId);
        saved.setAmount(BigDecimal.TEN);
        PaymentCommand command = new PaymentCommand(7L, null, "send without an assigned reference");
        when(idempotencyService.execute(
            eq("WORK_COMPLETION_ACT_SEND_TO_PAYMENT"), eq(actId), eq("work-pay-no-reference"), eq(command),
            eq(WorkCompletionActResponse.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(workCompletionActService.sendToPayment(actId, null, "send without an assigned reference", 7L))
            .thenReturn(saved);

        assertThat(service.sendWorkActToPayment(actId, command, "work-pay-no-reference")).isSameAs(saved);
        verify(workCompletionActService).sendToPayment(actId, null, "send without an assigned reference", 7L);
    }

    @Test
    void workActPaymentConfirmationDelegatesMarkPaidToActService() {
        UUID actId = UUID.randomUUID();
        PaymentCommand command = new PaymentCommand(5L, "BANK-WORK-5", "bank confirmed");
        WorkCompletionActResponse response = new WorkCompletionActResponse();
        when(idempotencyService.execute(
            eq("WORK_COMPLETION_ACT_MARK_PAID"), eq(actId), eq("work-paid-key"), eq(command),
            eq(WorkCompletionActResponse.class), any()
        )).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get());
        when(workCompletionActService.markPaid(actId, "BANK-WORK-5", "bank confirmed", 5L)).thenReturn(response);

        assertThat(service.markWorkActPaid(actId, command, "work-paid-key")).isSameAs(response);

        verify(workCompletionActService).markPaid(actId, "BANK-WORK-5", "bank confirmed", 5L);
        verify(workCompletionActService, never()).get(actId);
    }

    @Test
    void repeatedWorkPaymentCommandReplaysBeforeLockedMutation() {
        UUID actId = UUID.randomUUID();
        PaymentCommand command = new PaymentCommand(6L, "PAY-WORK-6", "ready");
        WorkCompletionActResponse response = new WorkCompletionActResponse();
        response.setId(actId);
        response.setAmount(BigDecimal.TEN);
        AtomicReference<WorkCompletionActResponse> cached = new AtomicReference<>();
        when(idempotencyService.execute(
            eq("WORK_COMPLETION_ACT_SEND_TO_PAYMENT"), eq(actId), eq("same-work-key"), eq(command),
            eq(WorkCompletionActResponse.class), any()
        )).thenAnswer(invocation -> {
            if (cached.get() != null) return cached.get();
            WorkCompletionActResponse first = (WorkCompletionActResponse) ((Supplier<?>) invocation.getArgument(5)).get();
            cached.set(first);
            return first;
        });
        when(workCompletionActService.sendToPayment(
            actId, "PAY-WORK-6", "ready", 6L
        )).thenReturn(response);

        assertThat(service.sendWorkActToPayment(actId, command, "same-work-key")).isSameAs(response);
        assertThat(service.sendWorkActToPayment(actId, command, "same-work-key")).isSameAs(response);

        verify(workCompletionActService).sendToPayment(actId, "PAY-WORK-6", "ready", 6L);
        verify(workCompletionActService, never()).get(actId);
    }

    @Test
    void replayLookupWinsBeforeVersionCheckAndChangedPayloadConflicts() {
        UUID requestId = UUID.randomUUID();
        VersionedActionCommand command = new VersionedActionCommand(4L, "ready for approval");
        ServiceRequestResponse before = new ServiceRequestResponse();
        before.setVersion(4L);
        ServiceRequestResponse original = new ServiceRequestResponse();
        original.setId(requestId);
        original.setVersion(5L);
        when(requestService.get(requestId)).thenReturn(before);
        when(requestService.submit(requestId)).thenReturn(original);
        AtomicReference<Object> firstPayload = new AtomicReference<>();
        AtomicReference<Object> cached = new AtomicReference<>();
        when(idempotencyService.execute(
            eq("SERVICE_REQUEST_SUBMIT"), eq(requestId), eq("same-key"), any(),
            eq(ServiceRequestResponse.class), any()
        )).thenAnswer(invocation -> {
            Object payload = invocation.getArgument(3);
            if (firstPayload.get() == null) {
                firstPayload.set(payload);
                Object response = ((Supplier<?>) invocation.getArgument(5)).get();
                cached.set(response);
                return response;
            }
            if (!firstPayload.get().equals(payload)) {
                throw new ConflictException("Idempotency-Key was already used with a different request payload");
            }
            return cached.get();
        });

        assertThat(service.submitRequest(requestId, command, "same-key")).isSameAs(original);
        assertThat(service.submitRequest(requestId, command, "same-key")).isSameAs(original);
        verify(requestService).get(requestId);
        assertThatThrownBy(() -> service.submitRequest(
            requestId, new VersionedActionCommand(4L, "changed reason"), "same-key"
        )).isInstanceOf(ConflictException.class).hasMessageContaining("different request payload");
    }

    private com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Workspace originWorkspace(
        UUID originId,
        String originType,
        String code,
        Long version
    ) {
        return new com.enterprise.mm.payload.ServiceAcceptanceWorkspaceDtos.Workspace(
            null, null, "NONE", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            originId, originType, originId, code, code, "ACTIVE", "EXECUTION", version, false,
            List.of(), List.of(), List.of(), java.util.Map.of(), null
        );
    }
}
