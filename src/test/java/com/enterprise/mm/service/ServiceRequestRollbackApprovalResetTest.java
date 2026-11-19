package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.enums.ServiceRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.ServiceRequestMapper;
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.ServicePartialAcceptRequest;
import com.enterprise.mm.payload.ServiceRequestCreateRequest;
import com.enterprise.mm.payload.ServiceRequestResponse;
import com.enterprise.mm.payload.ServiceRequestUpdateRequest;
import com.enterprise.mm.payload.ServiceRemarksRequest;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.ServiceRequestMilestoneRepository;
import com.enterprise.mm.repository.ServiceRequestRemarkRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ServiceRequestApprovalAdapter;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ServiceRequestRollbackApprovalResetTest {

    @Mock
    private ServiceRequestRepository repository;
    @Mock
    private ServiceRequestMapper mapper;
    @Mock
    private AuditService auditService;
    @Mock
    private ApprovalEngineService approvalEngineService;
    @Mock
    private ServiceWorkflowNotificationService notificationService;
    @Mock
    private ServiceReviewHistoryService historyService;
    @Mock
    private ServiceClaimRepository claimRepository;
    @Mock
    private RoleDataScopeService roleDataScopeService;
    @Mock
    private ServiceWorkflowAccessService accessService;
    @Mock
    private BudgetItemService budgetItemService;
    @Mock private ServiceExecutionRepository executionRepository;
    @Mock private ServiceAcceptanceActRepository acceptanceActRepository;
    @Mock private ServiceOrderRepository serviceOrderRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private ServiceRequestRemarkRepository remarkRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private UserRepository userRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private ServiceRequestMilestoneRepository milestoneRepository;

    private ServiceRequestService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        service = new ServiceRequestService(
            repository,
            mapper,
            auditService,
            approvalEngineService,
            notificationService,
            historyService,
            claimRepository,
            roleDataScopeService,
            accessService,
            budgetItemService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void rollbackResetsApprovalBeforeReturningRequest() {
        ServiceRequest request = request(ServiceRequestStatus.APPROVED);
        ServiceActionRequest action = new ServiceActionRequest("Correct the request", null, null);
        ServiceRequestResponse response = new ServiceRequestResponse();
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(repository.save(request)).thenReturn(request);
        when(mapper.toResponse(request)).thenReturn(response);

        assertThat(service.rollback(request.getId(), action)).isSameAs(response);

        verify(approvalEngineService).resetForResubmission(
            ServiceRequestApprovalAdapter.MODULE_KEY,
            request.getId(),
            action.comment()
        );
        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.RETURNED.name());
    }

    @Test
    void rollbackDoesNotReleaseBudgetOrMutateRequestWhenApprovalResetFails() {
        ServiceRequest request = request(ServiceRequestStatus.APPROVAL_PENDING);
        UUID budgetItemId = UUID.randomUUID();
        request.setBudgetItemId(budgetItemId);
        request.setBudgetReservedAmount(BigDecimal.valueOf(120));
        ServiceActionRequest action = new ServiceActionRequest("Correct the request", null, null);
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        doThrow(new BusinessException("reset failed")).when(approvalEngineService).resetForResubmission(
            ServiceRequestApprovalAdapter.MODULE_KEY,
            request.getId(),
            action.comment()
        );

        assertThatThrownBy(() -> service.rollback(request.getId(), action))
            .isInstanceOf(BusinessException.class)
            .hasMessage("reset failed");

        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.APPROVAL_PENDING.name());
        assertThat(request.getBudgetReservedAmount()).isEqualByComparingTo("120");
        verify(repository, never()).save(any(ServiceRequest.class));
        verifyNoInteractions(budgetItemService);
    }

    @Test
    void partialAcceptanceRejectsStaleWorkspaceVersionBeforeMutation() {
        ServiceRequest request = request(ServiceRequestStatus.WAITING_ACCEPTANCE);
        request.setVersion(4L);
        request.setAcceptedVolume(BigDecimal.TEN);
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);

        assertThatThrownBy(() -> service.partialAccept(
            request.getId(),
            new ServicePartialAcceptRequest(BigDecimal.ONE, BigDecimal.ZERO, "Accepted"),
            3L
        ))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        assertThat(request.getAcceptedVolume()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.WAITING_ACCEPTANCE.name());
        verify(repository, never()).save(any(ServiceRequest.class));
        verifyNoInteractions(historyService, auditService);
    }

    @Test
    void aggregatePartialAcceptanceCannotBypassPersistedMilestones() {
        ServiceRequest request = request(ServiceRequestStatus.WAITING_ACCEPTANCE);
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(milestoneRepository.existsByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, request.getId()
        )).thenReturn(true);

        ReflectionTestUtils.setField(service, "milestoneRepository", milestoneRepository);

        assertThatThrownBy(() -> service.partialAccept(
            request.getId(), new ServicePartialAcceptRequest(BigDecimal.ONE, BigDecimal.ZERO, "Bypass")
        )).isInstanceOf(BusinessException.class).hasMessageContaining("milestone acceptance endpoint");

        verify(repository, never()).save(any());
    }

    @Test
    void amountAcceptanceExposesMoneyWithoutInventingQuantity() {
        ServiceRequest request = request(ServiceRequestStatus.WAITING_ACCEPTANCE);
        request.setApprovedAmount(BigDecimal.valueOf(100));
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(repository.save(request)).thenReturn(request);

        ServiceRequestResponse response = canonicalService().partialAccept(
            request.getId(),
            new ServicePartialAcceptRequest(BigDecimal.valueOf(30), BigDecimal.ZERO, "Stage accepted")
        );

        assertThat(read(response, "getAcceptanceMode")).isEqualTo("AMOUNT");
        assertThat((BigDecimal) read(response, "getAcceptedAmount")).isEqualByComparingTo("30");
        assertThat(read(response, "getAcceptedQuantity")).isNull();
    }

    @Test
    void quantityAcceptanceUsesOrderedQuantityRatherThanOrderedMoneyAsItsLimit() {
        ServiceRequest request = request(ServiceRequestStatus.WAITING_ACCEPTANCE);
        request.setApprovedAmount(BigDecimal.valueOf(1000));
        write(request, "setAcceptanceMode", String.class, "QUANTITY");
        write(request, "setOrderedQuantity", BigDecimal.class, BigDecimal.TEN);
        write(request, "setUomCode", String.class, "HOUR");
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(repository.save(request)).thenReturn(request);

        ServiceRequestResponse response = canonicalService().partialAccept(
            request.getId(),
            new ServicePartialAcceptRequest(BigDecimal.valueOf(6), BigDecimal.ZERO, "Six hours accepted")
        );

        assertThat((BigDecimal) read(response, "getAcceptedQuantity")).isEqualByComparingTo("6");
        assertThat((BigDecimal) read(response, "getRemainingQuantity")).isEqualByComparingTo("4");
        assertThat(read(response, "getUomCode")).isEqualTo("HOUR");
    }

    @Test
    void quantityAcceptanceRejectsOverAcceptanceAgainstOrderedQuantity() {
        ServiceRequest request = request(ServiceRequestStatus.WAITING_ACCEPTANCE);
        request.setApprovedAmount(BigDecimal.valueOf(1000));
        write(request, "setAcceptanceMode", String.class, "QUANTITY");
        write(request, "setOrderedQuantity", BigDecimal.class, BigDecimal.TEN);
        write(request, "setUomCode", String.class, "HOUR");
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);

        assertThatThrownBy(() -> canonicalService().partialAccept(
            request.getId(),
            new ServicePartialAcceptRequest(BigDecimal.valueOf(11), BigDecimal.ZERO, "Too many hours")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ordered quantity");
    }

    @Test
    void rejectedAmountCanBeCorrectedAndReclassifiedAsAccepted() {
        ServiceRequest request = request(ServiceRequestStatus.WAITING_ACCEPTANCE);
        request.setApprovedAmount(BigDecimal.TEN);
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(repository.save(request)).thenReturn(request);
        ServiceRequestService canonical = canonicalService();

        canonical.partialAccept(
            request.getId(),
            new ServicePartialAcceptRequest(BigDecimal.valueOf(6), BigDecimal.valueOf(4), "Four units require rework")
        );
        ServiceRequestResponse corrected = canonical.partialAccept(
            request.getId(),
            new ServicePartialAcceptRequest(BigDecimal.valueOf(4), BigDecimal.ZERO, "Rework accepted")
        );

        assertThat(corrected.getAcceptedAmount()).isEqualByComparingTo("10");
        assertThat(corrected.getRejectedAmount()).isEqualByComparingTo("0");
        assertThat(corrected.getStatus()).isEqualTo("ACCEPTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_STARTED", "COMPLETED_BY_CONTRACTOR", "REJECTED"})
    void fullAcceptanceCannotBypassUnacceptedExecution(String executionStatus) {
        ServiceRequest request = request(ServiceRequestStatus.WAITING_ACCEPTANCE);
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        ServiceExecution execution = new ServiceExecution();
        execution.setStatus(executionStatus);
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", request.getId()))
            .thenReturn(List.of(execution));

        assertThatThrownBy(() -> canonicalFullService().partialAccept(
            request.getId(),
            new ServicePartialAcceptRequest(BigDecimal.valueOf(100), BigDecimal.ZERO, "Attempted final acceptance")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("executions");

        assertThat(request.getStatus()).isEqualTo("WAITING_ACCEPTANCE");
        assertThat(request.getAcceptedAmount()).isNull();
        verify(repository, never()).save(any(ServiceRequest.class));
    }

    @Test
    void partialAcceptanceIsDeniedBeforeWaitingAcceptanceStage() {
        ServiceRequest request = request(ServiceRequestStatus.APPROVED);
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);

        assertThatThrownBy(() -> canonicalFullService().partialAccept(
            request.getId(),
            new ServicePartialAcceptRequest(BigDecimal.valueOf(10), BigDecimal.ZERO, "Too early")
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not ready");

        verify(repository, never()).save(any(ServiceRequest.class));
    }

    @Test
    void fullAcceptanceCompletesAfterAllRequiredExecutionsAreAccepted() {
        ServiceRequest request = request(ServiceRequestStatus.WAITING_ACCEPTANCE);
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        ServiceExecution accepted = new ServiceExecution();
        accepted.setStatus(ServiceExecutionStatus.ACCEPTED.name());
        ServiceExecution acceptedWithRemarks = new ServiceExecution();
        acceptedWithRemarks.setStatus(ServiceExecutionStatus.ACCEPTED_WITH_REMARKS.name());
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", request.getId()))
            .thenReturn(List.of(accepted, acceptedWithRemarks));
        when(repository.save(request)).thenReturn(request);

        ServiceRequestResponse response = canonicalFullService().partialAccept(
            request.getId(),
            new ServicePartialAcceptRequest(BigDecimal.valueOf(100), BigDecimal.ZERO, "Accepted")
        );

        assertThat(response.getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void createRejectsNegativeAmountBeforePersistence() {
        ServiceRequestCreateRequest create = validCreate();
        create.setEstimatedAmount(BigDecimal.valueOf(-1));

        assertThatThrownBy(() -> canonicalService().create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("amount");

        verify(repository, never()).save(any(ServiceRequest.class));
    }

    @Test
    void createRejectsInvertedPlannedPeriodBeforePersistence() {
        ServiceRequestCreateRequest create = validCreate();
        create.setPlannedStartDate(LocalDate.of(2026, 4, 2));
        create.setPlannedEndDate(LocalDate.of(2026, 4, 1));

        assertThatThrownBy(() -> canonicalService().create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("period");

        verify(repository, never()).save(any(ServiceRequest.class));
    }

    @Test
    void legacyDraftCanBeSavedBeforeItsAmountBasisIsKnown() {
        ServiceRequestCreateRequest create = new ServiceRequestCreateRequest();
        create.setName("Legacy draft awaiting commercial scope");
        when(repository.save(any(ServiceRequest.class))).thenAnswer(invocation -> {
            ServiceRequest saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            saved.setTenantId("default");
            return saved;
        });

        ServiceRequestResponse response = canonicalService().create(create);

        assertThat(response.getStatus()).isEqualTo("DRAFT");
        assertThat(response.getOrderedAmount()).isNull();
    }

    @Test
    void legacyDraftCannotBeSubmittedUntilPositiveAcceptanceBasisIsDefined() {
        ServiceRequest request = request(ServiceRequestStatus.DRAFT);
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(null);
        request.setEstimatedAmount(BigDecimal.ZERO);
        request.setApprovedAmount(BigDecimal.ZERO);
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);

        assertThatThrownBy(() -> canonicalService().submit(request.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("orderedAmount");

        verifyNoInteractions(approvalEngineService);
    }

    @Test
    void guidedAmountIntakeRequiresPositiveOrderedAmount() {
        ServiceRequestCreateRequest create = new ServiceRequestCreateRequest();
        create.setName("Guided amount intake");
        create.setAcceptanceMode("AMOUNT");

        assertThatThrownBy(() -> canonicalService().create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("orderedAmount");

        verify(repository, never()).save(any(ServiceRequest.class));
    }

    @Test
    void nonDraftRequestCannotBeSoftDeleted() {
        ServiceRequest request = request(ServiceRequestStatus.APPROVED);
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);

        assertThatThrownBy(() -> service.softDelete(request.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("DRAFT");

        assertThat(request.isDeleted()).isFalse();
    }

    @Test
    void restoreRejectsRequestFromAnotherTenant() {
        ServiceRequest request = request(ServiceRequestStatus.DRAFT);
        request.setTenantId("other-tenant");
        request.softDelete();
        when(repository.findAnyById(request.getId())).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.restore(request.getId()))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);

        assertThat(request.isDeleted()).isTrue();
    }

    @Test
    void closeRequiresPaidAcceptanceWithExternalReference() {
        ServiceRequest request = request(ServiceRequestStatus.PAID);
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(claimRepository.findByTenantIdAndServiceRequestIdAndStatusNotAndIsDeletedFalse(
            "default", request.getId(), "CLOSED"
        )).thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.close(request.getId(), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("payment reference");
    }

    @Test
    void closeRejectsRequestWhenOnlyFirstStageIsPaid() {
        ServiceRequest request = request(ServiceRequestStatus.PAID);
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        request.setAcceptedAmount(BigDecimal.valueOf(100));
        request.setRejectedAmount(BigDecimal.ZERO);
        ServiceExecution execution = new ServiceExecution();
        execution.setStatus(ServiceExecutionStatus.ACCEPTED.name());
        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setServiceRequestId(request.getId());
        ServiceAcceptanceAct first = paidAct(request.getId(), order.getId(), BigDecimal.valueOf(40), "TREASURY-1");
        ServiceAcceptanceAct second = paidAct(request.getId(), order.getId(), BigDecimal.valueOf(60), null);
        second.setStatus(ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name());
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(claimRepository.findByTenantIdAndServiceRequestIdAndStatusNotAndIsDeletedFalse(
            "default", request.getId(), "CLOSED"
        )).thenReturn(java.util.List.of());
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", request.getId()))
            .thenReturn(java.util.List.of(execution));
        when(acceptanceActRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, request.getId()
        )).thenReturn(java.util.List.of(first, second));
        assertThatThrownBy(() -> canonicalFullService().close(request.getId(), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("All active service acts");

        assertThat(request.getStatus()).isEqualTo("PAID");
        verify(repository, never()).save(any(ServiceRequest.class));
    }

    @Test
    void closeIgnoresRejectedAndCancelledHistoryAfterPaidReplacement() {
        ServiceRequest request = request(ServiceRequestStatus.PAID);
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        request.setAcceptedAmount(BigDecimal.valueOf(100));
        request.setRejectedAmount(BigDecimal.ZERO);
        ServiceExecution execution = new ServiceExecution();
        execution.setStatus(ServiceExecutionStatus.ACCEPTED.name());
        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setServiceRequestId(request.getId());
        ServiceAcceptanceAct rejected = paidAct(
            request.getId(), order.getId(), BigDecimal.valueOf(100), null
        );
        rejected.setStatus(ServiceAcceptanceActStatus.REJECTED.name());
        ServiceAcceptanceAct cancelled = paidAct(
            request.getId(), order.getId(), BigDecimal.valueOf(100), null
        );
        cancelled.setStatus("CANCELLED");
        ServiceAcceptanceAct replacement = paidAct(
            request.getId(), order.getId(), BigDecimal.valueOf(100), "TREASURY-REPLACEMENT"
        );
        AttachmentDto signedAct = new AttachmentDto();
        signedAct.setAttachmentType("ACT");
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(claimRepository.findByTenantIdAndServiceRequestIdAndStatusNotAndIsDeletedFalse(
            "default", request.getId(), "CLOSED"
        )).thenReturn(List.of());
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", request.getId()))
            .thenReturn(List.of(execution));
        when(acceptanceActRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, request.getId()
        )).thenReturn(List.of(rejected, cancelled, replacement));
        when(serviceOrderRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, request.getId()
        )).thenReturn(List.of(order));
        when(attachmentService.list("SERVICE_ACCEPTANCE_ACT", replacement.getId())).thenReturn(List.of(signedAct));
        when(repository.save(request)).thenReturn(request);

        assertThatCode(() -> canonicalFullService().close(request.getId(), null))
            .doesNotThrowAnyException();

        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.CLOSED.name());
        assertThat(request.getActualAmount()).isEqualByComparingTo("100");
    }

    @Test
    void createRejectsSupplierFromAnotherCompany() {
        UUID supplierId = UUID.randomUUID();
        Supplier crossCompanySupplier = new Supplier();
        crossCompanySupplier.setId(supplierId);
        crossCompanySupplier.setTenantId("default");
        crossCompanySupplier.setCompanyId(UUID.randomUUID());
        ServiceRequestCreateRequest create = validCreate();
        create.setSupplierId(supplierId);
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse("default", supplierId))
            .thenReturn(Optional.of(crossCompanySupplier));

        assertThatThrownBy(() -> linkedScopeService().create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Supplier");

        verify(repository, never()).save(any(ServiceRequest.class));
    }

    @Test
    void departmentWorkerCannotMoveDraftToAnotherDepartment() {
        UUID ownDepartmentId = UUID.randomUUID();
        UUID otherDepartmentId = UUID.randomUUID();
        ServiceRequest request = request(ServiceRequestStatus.DRAFT);
        request.setDepartmentId(ownDepartmentId);
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        Department otherDepartment = new Department();
        otherDepartment.setId(otherDepartmentId);
        otherDepartment.setTenantId("default");
        otherDepartment.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        when(departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
            otherDepartmentId, "default", CompanyContext.DEFAULT_COMPANY_ID
        )).thenReturn(Optional.of(otherDepartment));
        when(roleDataScopeService.currentScope()).thenReturn(new RoleDataScopeService.DataScope(
            true, false, false, Set.of("worker"), Set.of("worker"), Set.of(), ownDepartmentId, null
        ));
        authenticate("worker", "DEPARTMENT_WORKER");
        ServiceRequestUpdateRequest update = amountUpdate();
        update.setDepartmentId(otherDepartmentId);

        assertThatThrownBy(() -> linkedScopeService().update(request.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("department");

        verify(repository, never()).save(any(ServiceRequest.class));
    }

    @Test
    void sameBudgetItemIncreaseReplacesExistingReservation() {
        UUID budgetItemId = UUID.randomUUID();
        ServiceRequest request = request(ServiceRequestStatus.DRAFT);
        request.setBudgetItemId(budgetItemId);
        request.setBudgetReservedAmount(BigDecimal.valueOf(100));
        existingForUpdate(request);
        ServiceRequestUpdateRequest update = amountUpdate();
        update.setBudgetItemId(budgetItemId);
        update.setEstimatedAmount(BigDecimal.valueOf(150));
        update.setOrderedAmount(BigDecimal.valueOf(150));

        canonicalService().update(request.getId(), update);

        verify(budgetItemService).release(budgetItemId, BigDecimal.valueOf(100), "ServiceRequest " + request.getCode());
        verify(budgetItemService).reserve(budgetItemId, BigDecimal.valueOf(150), "ServiceRequest " + request.getCode());
        assertThat(request.getBudgetReservedAmount()).isEqualByComparingTo("150");
        assertThat(request.getBudgetReservedAt()).isNotNull();
        assertThat(request.getBudgetReleasedAt()).isNull();
    }

    @Test
    void sameBudgetItemDecreaseReplacesExistingReservation() {
        UUID budgetItemId = UUID.randomUUID();
        ServiceRequest request = request(ServiceRequestStatus.DRAFT);
        request.setBudgetItemId(budgetItemId);
        request.setBudgetReservedAmount(BigDecimal.valueOf(100));
        existingForUpdate(request);
        ServiceRequestUpdateRequest update = amountUpdate();
        update.setBudgetItemId(budgetItemId);
        update.setEstimatedAmount(BigDecimal.valueOf(60));
        update.setOrderedAmount(BigDecimal.valueOf(60));

        canonicalService().update(request.getId(), update);

        verify(budgetItemService).release(budgetItemId, BigDecimal.valueOf(100), "ServiceRequest " + request.getCode());
        verify(budgetItemService).reserve(budgetItemId, BigDecimal.valueOf(60), "ServiceRequest " + request.getCode());
        assertThat(request.getBudgetReservedAmount()).isEqualByComparingTo("60");
    }

    @Test
    void zeroReservationBecomesPositiveOnSameBudgetItem() {
        UUID budgetItemId = UUID.randomUUID();
        ServiceRequest request = request(ServiceRequestStatus.DRAFT);
        request.setBudgetItemId(budgetItemId);
        request.setBudgetReservedAmount(BigDecimal.ZERO);
        existingForUpdate(request);
        ServiceRequestUpdateRequest update = amountUpdate();
        update.setBudgetItemId(budgetItemId);
        update.setEstimatedAmount(BigDecimal.valueOf(75));
        update.setOrderedAmount(BigDecimal.valueOf(75));

        canonicalService().update(request.getId(), update);

        verify(budgetItemService).reserve(budgetItemId, BigDecimal.valueOf(75), "ServiceRequest " + request.getCode());
        assertThat(request.getBudgetReservedAmount()).isEqualByComparingTo("75");
    }

    @Test
    void failedReplacementReservationDoesNotPersistOrRewriteReservationMetadata() {
        UUID budgetItemId = UUID.randomUUID();
        ServiceRequest request = request(ServiceRequestStatus.DRAFT);
        request.setBudgetItemId(budgetItemId);
        request.setBudgetReservedAmount(BigDecimal.valueOf(100));
        java.time.LocalDateTime originalReservedAt = java.time.LocalDateTime.of(2026, 1, 1, 10, 0);
        request.setBudgetReservedAt(originalReservedAt);
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        ServiceRequestUpdateRequest update = amountUpdate();
        update.setBudgetItemId(budgetItemId);
        update.setEstimatedAmount(BigDecimal.valueOf(150));
        update.setOrderedAmount(BigDecimal.valueOf(150));
        doThrow(new BusinessException("reservation failed")).when(budgetItemService)
            .reserve(eq(budgetItemId), eq(BigDecimal.valueOf(150)), anyString());

        assertThatThrownBy(() -> canonicalService().update(request.getId(), update))
            .isInstanceOf(BusinessException.class)
            .hasMessage("reservation failed");

        assertThat(request.getBudgetReservedAmount()).isEqualByComparingTo("100");
        assertThat(request.getBudgetReservedAt()).isEqualTo(originalReservedAt);
        assertThat(request.getBudgetReleasedAt()).isNull();
        verify(repository, never()).save(any(ServiceRequest.class));
    }

    @Test
    void submitRemarksRejectsAssigneeFromAnotherCompany() {
        ServiceRequest request = request(ServiceRequestStatus.IN_PROGRESS);
        UUID assigneeId = UUID.randomUUID();
        User crossCompanyUser = new User();
        crossCompanyUser.setId(assigneeId);
        crossCompanyUser.setTenantId("default");
        crossCompanyUser.setCompanyId(UUID.randomUUID());
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(userRepository.findByIdAndIsDeletedFalse(assigneeId)).thenReturn(Optional.of(crossCompanyUser));
        ServiceRemarksRequest remarks = new ServiceRemarksRequest(List.of(
            new ServiceRemarksRequest.Remark("NORMAL", "Resolve evidence gap", null, assigneeId)
        ));

        assertThatThrownBy(() -> linkedScopeService().submitRemarks(request.getId(), remarks))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class)
            .hasMessageContaining("Assigned user not found");

        verify(remarkRepository, never()).save(any());
    }

    @Test
    void draftCanSwitchFromQuantityToAmountWhenAcceptanceHasNotStarted() {
        ServiceRequest request = request(ServiceRequestStatus.DRAFT);
        request.setAcceptanceMode("QUANTITY");
        request.setOrderedQuantity(BigDecimal.TEN);
        request.setAcceptedQuantity(BigDecimal.ZERO);
        request.setRejectedQuantity(BigDecimal.ZERO);
        request.setUomCode("HOUR");
        existingForUpdate(request);
        ServiceRequestUpdateRequest update = amountUpdate();

        ServiceRequestResponse response = canonicalService().update(request.getId(), update);

        assertThat(response.getAcceptanceMode()).isEqualTo("AMOUNT");
        assertThat(response.getOrderedAmount()).isEqualByComparingTo("100");
        assertThat(response.getOrderedQuantity()).isNull();
        assertThat(response.getAcceptedQuantity()).isNull();
        assertThat(response.getUomCode()).isNull();
    }

    @Test
    void draftCanSwitchFromAmountToQuantityWhenAcceptanceHasNotStarted() {
        ServiceRequest request = request(ServiceRequestStatus.DRAFT);
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        request.setAcceptedAmount(BigDecimal.ZERO);
        request.setRejectedAmount(BigDecimal.ZERO);
        request.setCurrencyCode("UZS");
        existingForUpdate(request);
        ServiceRequestUpdateRequest update = new ServiceRequestUpdateRequest();
        update.setCode(request.getCode());
        update.setName(request.getName());
        update.setAcceptanceMode("QUANTITY");
        update.setOrderedQuantity(BigDecimal.TEN);
        update.setUomCode("HOUR");

        ServiceRequestResponse response = canonicalService().update(request.getId(), update);

        assertThat(response.getAcceptanceMode()).isEqualTo("QUANTITY");
        assertThat(response.getOrderedQuantity()).isEqualByComparingTo("10");
        assertThat(response.getOrderedAmount()).isNull();
        assertThat(response.getAcceptedAmount()).isNull();
        assertThat(response.getCurrencyCode()).isNull();
    }

    @Test
    void acceptanceModeCannotSwitchAfterQuantityAcceptanceHasStarted() {
        ServiceRequest request = request(ServiceRequestStatus.DRAFT);
        request.setAcceptanceMode("QUANTITY");
        request.setOrderedQuantity(BigDecimal.TEN);
        request.setAcceptedQuantity(BigDecimal.ONE);
        request.setRejectedQuantity(BigDecimal.ZERO);
        request.setAcceptedVolume(BigDecimal.ONE);
        request.setUomCode("HOUR");
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);

        assertThatThrownBy(() -> canonicalService().update(request.getId(), amountUpdate()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot change");

        assertThat(request.getAcceptanceMode()).isEqualTo("QUANTITY");
        assertThat(request.getAcceptedQuantity()).isEqualByComparingTo("1");
        verify(repository, never()).save(any(ServiceRequest.class));
    }

    private ServiceRequest request(ServiceRequestStatus status) {
        ServiceRequest request = new ServiceRequest();
        request.setId(UUID.randomUUID());
        request.setTenantId("default");
        request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        request.setCode("SR-ROLLBACK");
        request.setName("Request to correct");
        request.setStatus(status.name());
        request.setBudgetReservedAmount(BigDecimal.ZERO);
        return request;
    }

    private ServiceRequestCreateRequest validCreate() {
        ServiceRequestCreateRequest create = new ServiceRequestCreateRequest();
        create.setCode("SR-CANONICAL");
        create.setName("Canonical service request");
        create.setEstimatedAmount(BigDecimal.valueOf(100));
        create.setPlannedStartDate(LocalDate.of(2026, 4, 1));
        create.setPlannedEndDate(LocalDate.of(2026, 4, 30));
        return create;
    }

    private ServiceRequestUpdateRequest amountUpdate() {
        ServiceRequestUpdateRequest update = new ServiceRequestUpdateRequest();
        update.setCode("SR-CANONICAL");
        update.setName("Canonical service request");
        update.setAcceptanceMode("AMOUNT");
        update.setOrderedAmount(BigDecimal.valueOf(100));
        update.setCurrencyCode("UZS");
        update.setEstimatedAmount(BigDecimal.valueOf(100));
        return update;
    }

    private void existingForUpdate(ServiceRequest request) {
        when(repository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceRequest(request)).thenReturn(true);
        when(repository.save(request)).thenReturn(request);
    }

    private ServiceRequestService canonicalService() {
        return new ServiceRequestService(
            repository,
            new ServiceRequestMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            auditService,
            approvalEngineService,
            notificationService,
            historyService,
            claimRepository,
            roleDataScopeService,
            accessService,
            budgetItemService
        );
    }

    private ServiceRequestService canonicalFullService() {
        return new ServiceRequestService(
            repository,
            new ServiceRequestMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            auditService,
            approvalEngineService,
            notificationService,
            historyService,
            claimRepository,
            roleDataScopeService,
            accessService,
            budgetItemService,
            null,
            null,
            null,
            executionRepository,
            acceptanceActRepository,
            serviceOrderRepository,
            attachmentService
        );
    }

    private ServiceRequestService linkedScopeService() {
        return new ServiceRequestService(
            repository,
            new ServiceRequestMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            auditService,
            approvalEngineService,
            notificationService,
            historyService,
            claimRepository,
            roleDataScopeService,
            accessService,
            budgetItemService,
            remarkRepository,
            contractRepository,
            userRepository,
            executionRepository,
            acceptanceActRepository,
            serviceOrderRepository,
            attachmentService,
            departmentRepository,
            supplierRepository,
            purchaseOrderRepository
        );
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username,
            "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_" + role))
        ));
    }

    private ServiceAcceptanceAct paidAct(UUID requestId, UUID orderId, BigDecimal amount, String reference) {
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setTenantId("default");
        act.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        act.setStatus(ServiceAcceptanceActStatus.PAID.name());
        act.setServiceRequestId(requestId);
        act.setServiceOrderId(orderId);
        act.setAmount(amount);
        act.setPaymentReference(reference);
        return act;
    }

    private Object read(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Missing canonical acceptance accessor " + methodName, ex);
        }
    }

    private void write(Object target, String methodName, Class<?> argumentType, Object value) {
        try {
            Method method = target.getClass().getMethod(methodName, argumentType);
            method.invoke(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Missing canonical acceptance mutator " + methodName, ex);
        }
    }
}
