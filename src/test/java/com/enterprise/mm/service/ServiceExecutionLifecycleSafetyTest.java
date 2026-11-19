package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ServiceExecutionMapper;
import com.enterprise.mm.payload.ServiceExecutionResponse;
import com.enterprise.mm.payload.ServiceExecutionCreateRequest;
import com.enterprise.mm.payload.ServiceExecutionUpdateRequest;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceExecutionLifecycleSafetyTest {
    @Mock private ServiceExecutionRepository repository;
    @Mock private ServiceRequestRepository requestRepository;
    @Mock private ServiceOrderRepository orderRepository;
    @Mock private AuditService auditService;
    @Mock private ServiceWorkflowNotificationService notificationService;
    @Mock private ServiceReviewHistoryService historyService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ServiceWorkflowAccessService accessService;

    private final ServiceExecutionMapper mapper = new ServiceExecutionMapper();
    private ServiceExecutionService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        service = new ServiceExecutionService(
            repository,
            requestRepository,
            mapper,
            auditService,
            notificationService,
            historyService,
            roleDataScopeService,
            accessService,
            orderRepository
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void updateRejectsAcceptedExecutionBeforeMutatingEvidence() {
        UUID requestId = UUID.randomUUID();
        ServiceExecution execution = execution(ServiceExecutionStatus.ACCEPTED, requestId, UUID.randomUUID());
        ServiceExecutionUpdateRequest update = update(requestId, execution.getServiceOrderId());
        update.setExecutionReport("rewritten evidence");
        existing(execution, request(requestId));

        assertThatThrownBy(() -> service.update(execution.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be edited");

        assertThat(execution.getExecutionReport()).isEqualTo("original evidence");
    }

    @Test
    void updateRejectsServiceRequestReparenting() {
        UUID originalRequestId = UUID.randomUUID();
        UUID replacementRequestId = UUID.randomUUID();
        ServiceExecution execution = execution(ServiceExecutionStatus.IN_PROGRESS, originalRequestId, UUID.randomUUID());
        ServiceExecutionUpdateRequest update = update(replacementRequestId, execution.getServiceOrderId());
        existing(execution, request(originalRequestId));
        lenient().when(requestRepository.findByIdAndIsDeletedFalse(replacementRequestId)).thenReturn(Optional.of(request(replacementRequestId)));

        assertThatThrownBy(() -> service.update(execution.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("serviceRequestId");

        assertThat(execution.getServiceRequestId()).isEqualTo(originalRequestId);
    }

    @Test
    void updateRejectsServiceOrderReparenting() {
        UUID requestId = UUID.randomUUID();
        UUID originalOrderId = UUID.randomUUID();
        ServiceExecution execution = execution(ServiceExecutionStatus.PAUSED, requestId, originalOrderId);
        ServiceExecutionUpdateRequest update = update(requestId, UUID.randomUUID());
        existing(execution, request(requestId));

        assertThatThrownBy(() -> service.update(execution.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("serviceOrderId");

        assertThat(execution.getServiceOrderId()).isEqualTo(originalOrderId);
    }

    @Test
    void updateAllowsMutableExecutionAndPreservesStatus() {
        UUID requestId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        ServiceExecution execution = execution(ServiceExecutionStatus.IN_PROGRESS, requestId, orderId);
        ServiceExecutionUpdateRequest update = update(requestId, orderId);
        update.setExecutionReport("new evidence");
        existing(execution, request(requestId));

        ServiceExecutionResponse response = service.update(execution.getId(), update);

        assertThat(response.getStatus()).isEqualTo(ServiceExecutionStatus.IN_PROGRESS.name());
        assertThat(response.getExecutionReport()).isEqualTo("new evidence");
    }

    @Test
    void acceptingOneExecutionKeepsRequestWaitingUntilEveryRequiredExecutionIsAccepted() {
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        request.setStatus("WAITING_ACCEPTANCE");
        ServiceExecution acceptedNow = execution(ServiceExecutionStatus.COMPLETED_BY_CONTRACTOR, requestId, UUID.randomUUID());
        ServiceExecution stillInProgress = execution(ServiceExecutionStatus.IN_PROGRESS, requestId, UUID.randomUUID());
        existing(acceptedNow, request);
        when(repository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", requestId))
            .thenReturn(List.of(acceptedNow, stillInProgress));

        service.accept(acceptedNow.getId(), null);

        assertThat(request.getStatus()).isEqualTo("WAITING_ACCEPTANCE");
        assertThat(acceptedNow.getStatus()).isEqualTo(ServiceExecutionStatus.ACCEPTED.name());
    }

    @Test
    void rejectedExecutionCanReenterWorkAndBeResubmitted() {
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        ServiceExecution rejected = execution(ServiceExecutionStatus.REJECTED, requestId, UUID.randomUUID());
        existing(rejected, request);

        service.start(rejected.getId(), null);
        service.submit(rejected.getId(), null);

        assertThat(rejected.getStatus()).isEqualTo(ServiceExecutionStatus.COMPLETED_BY_CONTRACTOR.name());
        assertThat(rejected.getProgressPercent()).isEqualByComparingTo("100");
    }

    @Test
    void onlyNotStartedExecutionCanBeSoftDeletedOrRestored() {
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        ServiceExecution completed = execution(ServiceExecutionStatus.COMPLETED_BY_CONTRACTOR, requestId, UUID.randomUUID());
        existing(completed, request);

        assertThatThrownBy(() -> service.softDelete(completed.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOT_STARTED");

        completed.softDelete();
        when(repository.findAnyById(completed.getId())).thenReturn(Optional.of(completed));
        assertThatThrownBy(() -> service.restore(completed.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("NOT_STARTED");
    }

    @Test
    void createRejectsServiceOrderFromAnotherRequest() {
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        request.setStatus("APPROVED");
        ServiceOrder unrelatedOrder = new ServiceOrder();
        unrelatedOrder.setId(UUID.randomUUID());
        unrelatedOrder.setTenantId("default");
        unrelatedOrder.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        unrelatedOrder.setServiceRequestId(UUID.randomUUID());
        when(requestRepository.findByIdAndIsDeletedFalse(requestId)).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(orderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", unrelatedOrder.getId()))
            .thenReturn(Optional.of(unrelatedOrder));
        ServiceExecutionCreateRequest create = new ServiceExecutionCreateRequest();
        create.setName("Cross-linked execution");
        create.setServiceRequestId(requestId);
        create.setServiceOrderId(unrelatedOrder.getId());

        assertThatThrownBy(() -> service.create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Service order does not belong to service request");
    }

    private void existing(ServiceExecution execution, ServiceRequest request) {
        when(repository.findByIdAndIsDeletedFalse(execution.getId())).thenReturn(Optional.of(execution));
        when(requestRepository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(any(ServiceRequest.class))).thenReturn(true);
        when(accessService.canAccessServiceExecution(execution, request)).thenReturn(true);
        lenient().when(repository.save(any(ServiceExecution.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ServiceExecution execution(ServiceExecutionStatus status, UUID requestId, UUID orderId) {
        ServiceExecution execution = new ServiceExecution();
        execution.setId(UUID.randomUUID());
        execution.setTenantId("default");
        execution.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        execution.setCode("SE-1");
        execution.setName("Execution");
        execution.setStatus(status.name());
        execution.setServiceRequestId(requestId);
        execution.setServiceOrderId(orderId);
        execution.setExecutionReport("original evidence");
        return execution;
    }

    private ServiceRequest request(UUID id) {
        ServiceRequest request = new ServiceRequest();
        request.setId(id);
        request.setTenantId("default");
        request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        request.setStatus("IN_PROGRESS");
        return request;
    }

    private ServiceExecutionUpdateRequest update(UUID requestId, UUID orderId) {
        ServiceExecutionUpdateRequest update = new ServiceExecutionUpdateRequest();
        update.setCode("SE-1");
        update.setName("Execution");
        update.setServiceRequestId(requestId);
        update.setServiceOrderId(orderId);
        update.setExecutionReport("original evidence");
        return update;
    }
}
