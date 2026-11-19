package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.ServiceAcceptanceActMapper;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.ServiceAcceptanceActCreateRequest;
import com.enterprise.mm.payload.ServiceAcceptanceActResponse;
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ServiceAcceptanceActApprovalAdapter;
import com.enterprise.mm.service.approval.ServiceFinanceApprovalAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;

@ExtendWith(MockitoExtension.class)
class ServiceAcceptanceActServiceTest {
    @Mock private ServiceAcceptanceActRepository repository;
    @Mock private ServiceAcceptanceActMapper mapper;
    @Mock private ServiceOrderRepository serviceOrderRepository;
    @Mock private ServiceRequestRepository serviceRequestRepository;
    @Mock private ServiceExecutionRepository executionRepository;
    @Mock private ServiceClaimRepository claimRepository;
    @Mock private ContractService contractService;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private BudgetItemService budgetItemService;
    @Mock private AttachmentService attachmentService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private AuditService auditService;
    @Mock private ServiceReviewHistoryService historyService;
    @Mock private ServiceWorkflowNotificationService notificationService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ServiceWorkflowAccessService accessService;
    @Mock private ServiceOrderAcceptanceLifecycleService orderLifecycleService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void serviceOrderRepositoryExposesPessimisticWriteLockForAcceptanceCreation() {
        assertThat(java.util.Arrays.stream(ServiceOrderRepository.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("findByTenantIdAndIdForAcceptanceUpdate"))
            .toList())
            .singleElement()
            .satisfies(method -> assertThat(method.getAnnotation(Lock.class))
                .isNotNull()
                .extracting(Lock::value)
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE));
    }

    @Test
    void acceptanceCreationLocksTheOrderBeforeReadingCumulativeActCoverage() {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = readyOrder(requestId);
        when(serviceOrderRepository.findByTenantIdAndIdForAcceptanceUpdate("default", order.getId()))
            .thenReturn(Optional.of(order));
        when(repository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, order.getId()
        )).thenReturn(List.of());
        when(repository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> {
            ServiceAcceptanceAct saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        canonicalService().accept(
            order.getId(), "commission", "Final acceptance", BigDecimal.valueOf(100), "FINAL",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)
        );

        var ordered = org.mockito.Mockito.inOrder(serviceOrderRepository, repository);
        ordered.verify(serviceOrderRepository)
            .findByTenantIdAndIdForAcceptanceUpdate("default", order.getId());
        ordered.verify(repository, org.mockito.Mockito.atLeastOnce())
            .findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
                "default", CompanyContext.DEFAULT_COMPANY_ID, order.getId()
            );
    }

    @Test
    void acceptsTwoPartialStagesAndMarksOrderAcceptedOnlyAfterFinalCoverage() {
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        ServiceOrder order = order(requestId);
        stubOrder(order, request);
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", requestId))
            .thenReturn(List.of(execution(requestId, ServiceExecutionStatus.ACCEPTED)));
        List<ServiceAcceptanceAct> acts = new ArrayList<>();
        when(repository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, order.getId()
        )).thenAnswer(invocation -> List.copyOf(acts));
        when(repository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> {
            ServiceAcceptanceAct saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            saved.setTenantId("default");
            saved.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
            acts.add(saved);
            return saved;
        });
        when(serviceOrderRepository.save(order)).thenReturn(order);
        ServiceAcceptanceActService service = canonicalService();

        ServiceAcceptanceActResponse first = service.accept(
            order.getId(), "commission", "Foundation accepted", BigDecimal.valueOf(40), "FOUNDATION",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)
        );

        assertThat(first.getAmount()).isEqualByComparingTo("40");
        assertThat(first.getStage()).isEqualTo("FOUNDATION");
        assertThat(order.getStatus()).isEqualTo("ACT_PENDING");

        ServiceAcceptanceActResponse second = service.accept(
            order.getId(), "commission", "Roof accepted", BigDecimal.valueOf(60), "ROOF",
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)
        );

        assertThat(second.getAmount()).isEqualByComparingTo("60");
        assertThat(order.getStatus()).isEqualTo("ACT_PENDING");
        assertThat(acts).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "CANCELLED"})
    void rejectedOrCancelledStageCanBeReplaced(String inactiveStatus) {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = readyOrder(requestId);
        ServiceAcceptanceAct inactive = stagedAct(order, BigDecimal.valueOf(100), "FOUNDATION",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        inactive.setStatus(inactiveStatus);
        when(repository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, order.getId()
        )).thenReturn(List.of(inactive));
        when(repository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> {
            ServiceAcceptanceAct saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        ServiceAcceptanceActResponse replacement = canonicalService().accept(
            order.getId(), "commission", "Replacement", BigDecimal.valueOf(100), "foundation",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)
        );

        assertThat(replacement.getAmount()).isEqualByComparingTo("100");
    }

    @Test
    void stagedAcceptanceRejectsAmountAboveRemainingOrderValue() {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = readyOrder(requestId);
        ServiceAcceptanceAct existing = stagedAct(order, BigDecimal.valueOf(70), "FOUNDATION",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        when(repository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, order.getId()
        )).thenReturn(List.of(existing));

        assertThatThrownBy(() -> canonicalService().accept(
            order.getId(), "commission", "Overrun", BigDecimal.valueOf(40), "ROOF",
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("remaining");

        verify(repository, never()).save(any(ServiceAcceptanceAct.class));
    }

    @Test
    void stagedAcceptanceRejectsDuplicateStageAndPeriod() {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = readyOrder(requestId);
        ServiceAcceptanceAct existing = stagedAct(order, BigDecimal.valueOf(40), "FOUNDATION",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        when(repository.findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, order.getId()
        )).thenReturn(List.of(existing));

        assertThatThrownBy(() -> canonicalService().accept(
            order.getId(), "commission", "Duplicate", BigDecimal.TEN, "foundation",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Duplicate");

        verify(repository, never()).save(any(ServiceAcceptanceAct.class));
    }

    @Test
    void sendToPaymentConsumesDirectBudgetOnlyOnce() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID actId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID budgetItemId = UUID.randomUUID();

        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(actId);
        act.setTenantId("default");
        act.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        act.setCode("ACT-1");
        act.setServiceOrderId(orderId);
        act.setServiceRequestId(requestId);
        act.setStatus("APPROVED");
        act.setAmount(BigDecimal.valueOf(150));

        ServiceOrder order = new ServiceOrder();
        order.setId(orderId);
        order.setTenantId("default");
        order.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        order.setCode("SO-1");
        order.setServiceRequestId(requestId);

        ServiceRequest request = new ServiceRequest();
        request.setId(requestId);
        request.setTenantId("default");
        request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        request.setBudgetItemId(budgetItemId);
        request.setBudgetReservedAmount(BigDecimal.valueOf(150));

        AttachmentDto attachment = new AttachmentDto();
        attachment.setAttachmentType("ACT");
        ServiceAcceptanceActResponse response = new ServiceAcceptanceActResponse();

        when(repository.findByIdForUpdate(actId)).thenReturn(Optional.of(act));
        when(serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", orderId)).thenReturn(Optional.of(order));
        when(serviceRequestRepository.findByIdAndIsDeletedFalse(requestId)).thenReturn(Optional.of(request));
        when(claimRepository.findByTenantIdAndServiceRequestIdAndStatusNotAndIsDeletedFalse("default", requestId, "CLOSED")).thenReturn(List.of());
        when(claimRepository.findByTenantIdAndServiceActIdAndStatusNotAndIsDeletedFalse("default", actId, "CLOSED")).thenReturn(List.of());
        when(attachmentService.list("SERVICE_ACCEPTANCE_ACT", actId)).thenReturn(List.of(attachment));
        when(accessService.canAccessServiceAcceptanceAct(act, request, order)).thenReturn(true);
        when(repository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toResponse(any(ServiceAcceptanceAct.class))).thenReturn(response);

        ServiceAcceptanceActService service = new ServiceAcceptanceActService(
            repository,
            mapper,
            serviceOrderRepository,
            serviceRequestRepository,
            executionRepository,
            claimRepository,
            contractService,
            purchaseOrderRepository,
            purchaseOrderItemRepository,
            budgetItemService,
            attachmentService,
            integrationEventService,
            approvalEngineService,
            auditService,
            historyService,
            notificationService,
            roleDataScopeService,
            accessService,
            orderLifecycleService,
            org.mockito.Mockito.mock(ProcurementPayablesService.class)
        );

        service.sendToPayment(actId, "PAY-1", "first");
        service.sendToPayment(actId, "PAY-2", "second");

        assertThat(act.getSentToPaymentAt()).isNotNull();
        assertThat(act.getStatus()).isEqualTo("SENT_TO_PAYMENT");
        assertThat(request.getBudgetConsumedAt()).isNotNull();
        assertThat(request.getBudgetReservedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(budgetItemService).consume(budgetItemId, BigDecimal.valueOf(150), "ServiceAcceptanceAct ACT-1");
        verify(contractService, never()).consumeLimit(any(), any(), eq(true), any());
    }

    @Test
    void sendToPaymentRejectsStaleWorkspaceVersionBeforeFinancialMutation() {
        ServiceAcceptanceAct act = act("APPROVED", false);
        act.setVersion(8L);
        when(repository.findByIdForUpdate(act.getId())).thenReturn(Optional.of(act));
        when(accessService.canAccessServiceAcceptanceAct(act, null, null)).thenReturn(true);

        assertThatThrownBy(() -> service().sendToPayment(act.getId(), "PAY-8", "ready", 7L))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        verify(repository, never()).save(any(ServiceAcceptanceAct.class));
        verifyNoInteractions(budgetItemService, contractService, integrationEventService);
    }

    @Test
    void requestlessPoServiceActPaymentIsBlockedByOpenOrderClaim() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        ServiceOrder order = order(null);
        ServiceAcceptanceAct act = act("APPROVED", false);
        act.setServiceOrderId(order.getId());
        ServiceClaim openClaim = new ServiceClaim();
        openClaim.setId(UUID.randomUUID());
        openClaim.setStatus("SENT");
        openClaim.setServiceOrderId(order.getId());
        when(repository.findByIdForUpdate(act.getId())).thenReturn(Optional.of(act));
        when(serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", order.getId()))
            .thenReturn(Optional.of(order));
        when(accessService.canAccessServiceAcceptanceAct(act, null, order)).thenReturn(true);
        when(claimRepository.findByTenantIdAndServiceActIdAndStatusNotAndIsDeletedFalse(
            "default", act.getId(), "CLOSED"
        )).thenReturn(List.of());
        when(claimRepository.findByTenantIdAndServiceOrderIdAndStatusNotAndIsDeletedFalse(
            "default", order.getId(), "CLOSED"
        )).thenReturn(List.of(openClaim));

        assertThatThrownBy(() -> service().sendToPayment(act.getId(), "PAY-PO-SERVICE", "ready"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("open service claims");

        verify(attachmentService, never()).list(any(), any());
    }

    @Test
    void requestlessPoServiceActCannotBeMarkedPaidWhenClaimOpenedAfterPaymentHandoff() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        ServiceOrder order = order(null);
        ServiceAcceptanceAct act = act("SENT_TO_PAYMENT", false);
        act.setServiceOrderId(order.getId());
        act.setPaymentReference("TREASURY-PENDING");
        ServiceClaim openClaim = new ServiceClaim();
        openClaim.setId(UUID.randomUUID());
        openClaim.setStatus("SENT");
        openClaim.setServiceOrderId(order.getId());
        when(repository.findByIdForUpdate(act.getId())).thenReturn(Optional.of(act));
        when(serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", order.getId()))
            .thenReturn(Optional.of(order));
        when(accessService.canAccessServiceAcceptanceAct(act, null, order)).thenReturn(true);
        when(claimRepository.findByTenantIdAndServiceActIdAndStatusNotAndIsDeletedFalse(
            "default", act.getId(), "CLOSED"
        )).thenReturn(List.of());
        when(claimRepository.findByTenantIdAndServiceOrderIdAndStatusNotAndIsDeletedFalse(
            "default", order.getId(), "CLOSED"
        )).thenReturn(List.of(openClaim));

        assertThatThrownBy(() -> service().markPaid(act.getId(), "TREASURY-PAID", "callback"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("open service claims");

        assertThat(act.getStatus()).isEqualTo("SENT_TO_PAYMENT");
        assertThat(act.getPaidAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "TECHNICAL_REVIEW", "DOCUMENT_REVIEW", "FINANCE_REVIEW", "APPROVED", "SENT_TO_PAYMENT", "PAID"})
    void softDeleteRejectsReviewAndFinancialStates(String status) {
        ServiceAcceptanceAct act = act(status, false);
        when(repository.findByIdAndIsDeletedFalse(act.getId())).thenReturn(Optional.of(act));
        when(accessService.canAccessServiceAcceptanceAct(act, null, null)).thenReturn(true);

        assertThatThrownBy(() -> service().softDelete(act.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be deleted");

        assertThat(act.isDeleted()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "TECHNICAL_REVIEW", "DOCUMENT_REVIEW", "FINANCE_REVIEW", "APPROVED", "SENT_TO_PAYMENT", "PAID"})
    void restoreRejectsReviewAndFinancialStates(String status) {
        ServiceAcceptanceAct act = act(status, true);
        when(repository.findAnyById(act.getId())).thenReturn(Optional.of(act));
        when(accessService.canAccessServiceAcceptanceAct(act, null, null)).thenReturn(true);

        assertThatThrownBy(() -> service().restore(act.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be restored");

        assertThat(act.isDeleted()).isTrue();
    }

    @Test
    void draftCanBeDeletedAndRestored() {
        ServiceAcceptanceAct draft = act("DRAFT", false);
        draft.setServiceOrderId(UUID.randomUUID());
        when(repository.findByIdAndIsDeletedFalse(draft.getId())).thenReturn(Optional.of(draft));
        when(repository.findAnyById(draft.getId())).thenReturn(Optional.of(draft));
        when(accessService.canAccessServiceAcceptanceAct(draft, null, null)).thenReturn(true);
        when(repository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().softDelete(draft.getId());
        service().restore(draft.getId());

        assertThat(draft.isDeleted()).isFalse();
        verify(orderLifecycleService, times(2)).recompute(draft.getServiceOrderId());
    }

    @Test
    void createRejectsServiceRequestThatDoesNotBelongToOrder() {
        UUID orderRequestId = UUID.randomUUID();
        UUID unrelatedRequestId = UUID.randomUUID();
        ServiceOrder order = order(orderRequestId);
        ServiceRequest orderRequest = request(orderRequestId);
        ServiceExecution unrelatedAccepted = execution(unrelatedRequestId, ServiceExecutionStatus.ACCEPTED);
        stubOrder(order, orderRequest);
        lenient().when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", unrelatedRequestId))
            .thenReturn(List.of(unrelatedAccepted));

        ServiceAcceptanceActCreateRequest create = validCreate(order);
        create.setServiceRequestId(unrelatedRequestId);

        assertThatThrownBy(() -> canonicalService().create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("service request");
    }

    @Test
    void createRequiresEveryRequiredExecutionToBeAccepted() {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = order(requestId);
        stubOrder(order, request(requestId));
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", requestId))
            .thenReturn(List.of(
                execution(requestId, ServiceExecutionStatus.ACCEPTED),
                execution(requestId, ServiceExecutionStatus.IN_PROGRESS)
            ));

        assertThatThrownBy(() -> canonicalService().create(validCreate(order)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("all required");
    }

    @Test
    void createAllowsAnotherActForADifferentStageAndPeriod() {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = order(requestId);
        ServiceRequest request = request(requestId);
        ServiceAcceptanceAct existing = new ServiceAcceptanceAct();
        existing.setId(UUID.randomUUID());
        existing.setServiceOrderId(order.getId());
        existing.setServiceRequestId(requestId);
        existing.setStage("FOUNDATION");
        existing.setPeriodStart(LocalDate.of(2026, 1, 1));
        existing.setPeriodEnd(LocalDate.of(2026, 1, 31));
        stubOrder(order, request);
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", requestId))
            .thenReturn(List.of(execution(requestId, ServiceExecutionStatus.ACCEPTED)));
        lenient().when(repository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", requestId))
            .thenReturn(Optional.of(existing));
        lenient().when(repository.findByTenantIdAndServiceOrderIdAndIsDeletedFalse("default", order.getId()))
            .thenReturn(Optional.of(existing));
        when(repository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ServiceAcceptanceActCreateRequest create = validCreate(order);
        create.setStage("ROOF");
        create.setPeriodStart(LocalDate.of(2026, 2, 1));
        create.setPeriodEnd(LocalDate.of(2026, 2, 28));

        ServiceAcceptanceActResponse response = canonicalService().create(create);

        assertThat(response.getStage()).isEqualTo("ROOF");
    }

    @Test
    void createRejectsNonPositiveAmount() {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = readyOrder(requestId);
        ServiceAcceptanceActCreateRequest create = validCreate(order);
        create.setAmount(BigDecimal.ZERO);

        assertThatThrownBy(() -> canonicalService().create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("amount");
    }

    @Test
    void createRejectsVatRateAboveOneHundredPercent() {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = readyOrder(requestId);
        ServiceAcceptanceActCreateRequest create = validCreate(order);
        create.setVatRate(BigDecimal.valueOf(101));
        create.setVatAmount(BigDecimal.TEN);

        assertThatThrownBy(() -> canonicalService().create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("VAT");
    }

    @Test
    void createRejectsInvertedAcceptancePeriod() {
        UUID requestId = UUID.randomUUID();
        ServiceOrder order = readyOrder(requestId);
        ServiceAcceptanceActCreateRequest create = validCreate(order);
        create.setPeriodStart(LocalDate.of(2026, 3, 2));
        create.setPeriodEnd(LocalDate.of(2026, 3, 1));

        assertThatThrownBy(() -> canonicalService().create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("period");
    }

    @Test
    void markPaidRequiresExternalTreasuryReference() {
        ServiceAcceptanceAct act = act("SENT_TO_PAYMENT", false);
        when(repository.findByIdForUpdate(act.getId())).thenReturn(Optional.of(act));
        when(accessService.canAccessServiceAcceptanceAct(act, null, null)).thenReturn(true);

        assertThatThrownBy(() -> service().markPaid(act.getId(), " ", "bank confirmed", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("payment reference");

        assertThat(act.getStatus()).isEqualTo("SENT_TO_PAYMENT");
    }

    @Test
    void markPaidCompletesPurchaseOrderWhenPaidActsCoverOrderedAmount() {
        UUID purchaseOrderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        request.setStatus("SENT_TO_PAYMENT");
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        request.setAcceptedAmount(BigDecimal.valueOf(100));
        ServiceOrder order = order(requestId);
        order.setPurchaseOrderId(purchaseOrderId);
        ServiceAcceptanceAct act = act("SENT_TO_PAYMENT", false);
        act.setServiceRequestId(requestId);
        act.setServiceOrderId(order.getId());
        act.setPurchaseOrderId(purchaseOrderId);
        act.setAmount(BigDecimal.valueOf(100));
        stubPaymentAggregation(request, order, act, List.of(act));

        PurchaseOrder po = new PurchaseOrder();
        po.setId(purchaseOrderId);
        po.setStatus("PARTIALLY_COMPLETED");
        po.setTotalAmount(BigDecimal.valueOf(100));
        PurchaseOrderItem serviceLine = new PurchaseOrderItem();
        serviceLine.setId(UUID.randomUUID());
        serviceLine.setLineType(ProcurementLineType.SERVICE);
        serviceLine.setQuantity(BigDecimal.ONE);
        serviceLine.setUnitPrice(BigDecimal.valueOf(100));
        serviceLine.setStatus("PARTIALLY_COMPLETED");

        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(serviceLine));
        when(repository.findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
            eq("default"), eq(purchaseOrderId), eq(List.of("PAID"))
        )).thenAnswer(invocation -> {
            assertThat(act.getStatus()).isEqualTo("PAID");
            return List.of(act);
        });
        when(purchaseOrderItemRepository.save(any(PurchaseOrderItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().markPaid(act.getId(), "TREASURY-FULL", "Fully paid", null);

        assertThat(po.getStatus()).isEqualTo("COMPLETED");
        assertThat(serviceLine.getStatus()).isEqualTo("COMPLETED");
        verify(purchaseOrderRepository).save(po);
    }

    @Test
    void markPaidKeepsPurchaseOrderPartiallyCompletedWhenPaidCoverageIsIncomplete() {
        UUID purchaseOrderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        request.setStatus("SENT_TO_PAYMENT");
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        request.setAcceptedAmount(BigDecimal.valueOf(40));
        ServiceOrder order = order(requestId);
        order.setPurchaseOrderId(purchaseOrderId);
        ServiceAcceptanceAct first = act("SENT_TO_PAYMENT", false);
        first.setServiceRequestId(requestId);
        first.setServiceOrderId(order.getId());
        first.setPurchaseOrderId(purchaseOrderId);
        first.setAmount(BigDecimal.valueOf(40));
        ServiceAcceptanceAct second = act("SENT_TO_PAYMENT", false);
        second.setServiceRequestId(requestId);
        second.setServiceOrderId(order.getId());
        second.setPurchaseOrderId(purchaseOrderId);
        second.setAmount(BigDecimal.valueOf(60));
        stubPaymentAggregation(request, order, first, List.of(first, second));

        PurchaseOrder po = new PurchaseOrder();
        po.setId(purchaseOrderId);
        po.setStatus("PARTIALLY_COMPLETED");
        po.setTotalAmount(BigDecimal.valueOf(100));
        PurchaseOrderItem serviceLine = new PurchaseOrderItem();
        serviceLine.setId(UUID.randomUUID());
        serviceLine.setLineType(ProcurementLineType.SERVICE);
        serviceLine.setQuantity(BigDecimal.ONE);
        serviceLine.setUnitPrice(BigDecimal.valueOf(100));
        serviceLine.setStatus("PARTIALLY_COMPLETED");

        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(serviceLine));
        when(repository.findByTenantIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
            eq("default"), eq(purchaseOrderId), eq(List.of("PAID"))
        )).thenAnswer(invocation -> List.of(first));
        when(purchaseOrderItemRepository.save(any(PurchaseOrderItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().markPaid(first.getId(), "TREASURY-PARTIAL", "Partial paid", null);

        assertThat(po.getStatus()).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(serviceLine.getStatus()).isEqualTo("PARTIALLY_COMPLETED");
    }

    @Test
    void markingFirstStagePaidKeepsRequestPendingAndUsesCumulativeActAmount() {
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        request.setStatus("SENT_TO_PAYMENT");
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        request.setAcceptedAmount(BigDecimal.valueOf(100));
        ServiceOrder order = order(requestId);
        ServiceAcceptanceAct first = act("SENT_TO_PAYMENT", false);
        first.setServiceRequestId(requestId);
        first.setServiceOrderId(order.getId());
        first.setAmount(BigDecimal.valueOf(40));
        first.setStage("FOUNDATION");
        ServiceAcceptanceAct second = act("SENT_TO_PAYMENT", false);
        second.setServiceRequestId(requestId);
        second.setServiceOrderId(order.getId());
        second.setAmount(BigDecimal.valueOf(60));
        second.setStage("ROOF");
        when(repository.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(serviceRequestRepository.findByIdAndIsDeletedFalse(requestId)).thenReturn(Optional.of(request));
        when(serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", order.getId())).thenReturn(Optional.of(order));
        when(accessService.canAccessServiceAcceptanceAct(first, request, order)).thenReturn(true);
        when(repository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, requestId
        )).thenReturn(List.of(first, second));
        lenient().when(serviceOrderRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, requestId
        )).thenReturn(List.of(order));
        when(repository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().markPaid(first.getId(), "TREASURY-ACK-1", "First stage paid", null);

        assertThat(request.getStatus()).isEqualTo("SENT_TO_PAYMENT");
        assertThat(request.getActualAmount()).isEqualByComparingTo("100");
        assertThat(first.getStatus()).isEqualTo("PAID");
        assertThat(second.getStatus()).isEqualTo("SENT_TO_PAYMENT");
    }

    @Test
    void rejectedHistoricalActDoesNotBlockPaidReplacement() {
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        request.setStatus("SENT_TO_PAYMENT");
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        request.setAcceptedAmount(BigDecimal.valueOf(100));
        request.setRejectedAmount(BigDecimal.ZERO);
        ServiceOrder order = order(requestId);
        ServiceAcceptanceAct rejected = act("REJECTED", false);
        rejected.setServiceRequestId(requestId);
        rejected.setServiceOrderId(order.getId());
        rejected.setAmount(BigDecimal.valueOf(100));
        ServiceAcceptanceAct replacement = act("SENT_TO_PAYMENT", false);
        replacement.setServiceRequestId(requestId);
        replacement.setServiceOrderId(order.getId());
        replacement.setAmount(BigDecimal.valueOf(100));
        stubPaymentAggregation(request, order, replacement, List.of(rejected, replacement));

        service().markPaid(replacement.getId(), "TREASURY-REPLACEMENT", "Replacement paid", null);

        assertThat(replacement.getStatus()).isEqualTo("PAID");
        assertThat(request.getActualAmount()).isEqualByComparingTo("100");
        assertThat(request.getStatus()).isEqualTo("PAID");
    }

    @Test
    void activeDraftDoesNotCountAsActualAmountAndBlocksRequestPaid() {
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        request.setStatus("SENT_TO_PAYMENT");
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        request.setAcceptedAmount(BigDecimal.valueOf(100));
        request.setRejectedAmount(BigDecimal.ZERO);
        ServiceOrder order = order(requestId);
        ServiceAcceptanceAct paying = act("SENT_TO_PAYMENT", false);
        paying.setServiceRequestId(requestId);
        paying.setServiceOrderId(order.getId());
        paying.setAmount(BigDecimal.valueOf(100));
        ServiceAcceptanceAct draft = act("DRAFT", false);
        draft.setServiceRequestId(requestId);
        draft.setServiceOrderId(order.getId());
        draft.setAmount(BigDecimal.valueOf(25));
        stubPaymentAggregation(request, order, paying, List.of(paying, draft));

        service().markPaid(paying.getId(), "TREASURY-WITH-DRAFT", "Paid stage", null);

        assertThat(request.getActualAmount()).isEqualByComparingTo("100");
        assertThat(request.getStatus()).isEqualTo("SENT_TO_PAYMENT");
    }

    @Test
    void legacyRejectedVolumePreventsPrematureRequestPaidStatus() {
        UUID requestId = UUID.randomUUID();
        ServiceRequest request = request(requestId);
        request.setStatus("SENT_TO_PAYMENT");
        request.setAcceptanceMode("AMOUNT");
        request.setOrderedAmount(BigDecimal.valueOf(100));
        request.setAcceptedAmount(BigDecimal.valueOf(100));
        request.setRejectedAmount(null);
        request.setRejectedVolume(BigDecimal.TEN);
        ServiceOrder order = order(requestId);
        ServiceExecution execution = execution(requestId, ServiceExecutionStatus.ACCEPTED);
        ServiceAcceptanceAct act = act("SENT_TO_PAYMENT", false);
        act.setServiceRequestId(requestId);
        act.setServiceOrderId(order.getId());
        act.setAmount(BigDecimal.valueOf(100));
        when(repository.findByIdForUpdate(act.getId())).thenReturn(Optional.of(act));
        when(serviceRequestRepository.findByIdAndIsDeletedFalse(requestId)).thenReturn(Optional.of(request));
        when(serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", order.getId()))
            .thenReturn(Optional.of(order));
        when(accessService.canAccessServiceAcceptanceAct(act, request, order)).thenReturn(true);
        when(repository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, requestId
        )).thenReturn(List.of(act));
        when(serviceOrderRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, requestId
        )).thenReturn(List.of(order));
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", requestId))
            .thenReturn(List.of(execution));
        when(repository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().markPaid(act.getId(), "TREASURY-ACK-LEGACY", "Legacy stage paid", null);

        assertThat(request.getStatus()).isEqualTo("SENT_TO_PAYMENT");
    }

    @Test
    void sendToPaymentConsumesOnlyCurrentStageFromDirectRequestReservation() {
        UUID requestId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID budgetItemId = UUID.randomUUID();
        ServiceAcceptanceAct act = act("APPROVED", false);
        act.setServiceRequestId(requestId);
        act.setServiceOrderId(orderId);
        act.setAmount(BigDecimal.valueOf(40));
        ServiceOrder order = order(requestId);
        order.setId(orderId);
        ServiceRequest request = request(requestId);
        request.setBudgetItemId(budgetItemId);
        request.setBudgetReservedAmount(BigDecimal.valueOf(100));
        AttachmentDto attachment = new AttachmentDto();
        attachment.setAttachmentType("ACT");
        when(repository.findByIdForUpdate(act.getId())).thenReturn(Optional.of(act));
        when(serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", orderId)).thenReturn(Optional.of(order));
        when(serviceRequestRepository.findByIdAndIsDeletedFalse(requestId)).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceAcceptanceAct(act, request, order)).thenReturn(true);
        when(claimRepository.findByTenantIdAndServiceRequestIdAndStatusNotAndIsDeletedFalse("default", requestId, "CLOSED"))
            .thenReturn(List.of());
        when(claimRepository.findByTenantIdAndServiceActIdAndStatusNotAndIsDeletedFalse("default", act.getId(), "CLOSED"))
            .thenReturn(List.of());
        when(attachmentService.list("SERVICE_ACCEPTANCE_ACT", act.getId())).thenReturn(List.of(attachment));
        when(repository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, requestId
        )).thenReturn(List.of(act));
        when(repository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().sendToPayment(act.getId(), "PAYMENT-REQUEST-1", "Stage ready");

        verify(budgetItemService).consume(budgetItemId, BigDecimal.valueOf(40), "ServiceAcceptanceAct " + act.getCode());
        assertThat(request.getBudgetReservedAmount()).isEqualByComparingTo("60");
        assertThat(request.getBudgetConsumedAt()).isNull();
        assertThat(request.getActualAmount()).isEqualByComparingTo("40");
    }

    @Test
    void rollbackResetsTechnicalAndFinanceApprovalsBeforeReturningActToDraft() {
        ServiceAcceptanceAct act = act("FINANCE_REVIEW", false);
        act.setServiceOrderId(UUID.randomUUID());
        ServiceActionRequest action = new ServiceActionRequest("Correct the act", null, null);
        ServiceAcceptanceActResponse response = new ServiceAcceptanceActResponse();
        when(repository.findByIdAndIsDeletedFalse(act.getId())).thenReturn(Optional.of(act));
        when(accessService.canAccessServiceAcceptanceAct(act, null, null)).thenReturn(true);
        when(repository.save(act)).thenReturn(act);
        when(mapper.toResponse(act)).thenReturn(response);

        assertThat(service().rollback(act.getId(), action)).isSameAs(response);

        verify(approvalEngineService).resetForResubmission(
            ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
            act.getId(),
            action.comment()
        );
        verify(approvalEngineService).resetForResubmission(
            ServiceFinanceApprovalAdapter.MODULE_KEY,
            act.getId(),
            action.comment()
        );
        assertThat(act.getStatus()).isEqualTo("DRAFT");
        verify(orderLifecycleService).recompute(act.getServiceOrderId());
    }

    @Test
    void rollbackDoesNotMutateActWhenApprovalResetFails() {
        ServiceAcceptanceAct act = act("FINANCE_REVIEW", false);
        ServiceActionRequest action = new ServiceActionRequest("Correct the act", null, null);
        when(repository.findByIdAndIsDeletedFalse(act.getId())).thenReturn(Optional.of(act));
        when(accessService.canAccessServiceAcceptanceAct(act, null, null)).thenReturn(true);
        org.mockito.Mockito.doNothing().when(approvalEngineService).resetForResubmission(
            ServiceAcceptanceActApprovalAdapter.MODULE_KEY,
            act.getId(),
            action.comment()
        );
        doThrow(new BusinessException("reset failed")).when(approvalEngineService).resetForResubmission(
            ServiceFinanceApprovalAdapter.MODULE_KEY,
            act.getId(),
            action.comment()
        );

        assertThatThrownBy(() -> service().rollback(act.getId(), action))
            .isInstanceOf(BusinessException.class)
            .hasMessage("reset failed");

        assertThat(act.getStatus()).isEqualTo("FINANCE_REVIEW");
        verify(repository, never()).save(any(ServiceAcceptanceAct.class));
    }

    private ServiceAcceptanceAct act(String status, boolean deleted) {
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setId(UUID.randomUUID());
        act.setTenantId("default");
        act.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        act.setCode("ACT-LIFECYCLE");
        act.setStatus(status);
        if (deleted) {
            act.softDelete();
        }
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        return act;
    }

    private void stubPaymentAggregation(
        ServiceRequest request,
        ServiceOrder order,
        ServiceAcceptanceAct payingAct,
        List<ServiceAcceptanceAct> allActs
    ) {
        ServiceExecution acceptedExecution = execution(request.getId(), ServiceExecutionStatus.ACCEPTED);
        when(repository.findByIdForUpdate(payingAct.getId())).thenReturn(Optional.of(payingAct));
        when(serviceRequestRepository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", order.getId()))
            .thenReturn(Optional.of(order));
        when(accessService.canAccessServiceAcceptanceAct(payingAct, request, order)).thenReturn(true);
        when(claimRepository.findByTenantIdAndServiceRequestIdAndStatusNotAndIsDeletedFalse(
            "default", request.getId(), "CLOSED"
        )).thenReturn(List.of());
        when(claimRepository.findByTenantIdAndServiceActIdAndStatusNotAndIsDeletedFalse(
            "default", payingAct.getId(), "CLOSED"
        )).thenReturn(List.of());
        when(claimRepository.findByTenantIdAndServiceOrderIdAndStatusNotAndIsDeletedFalse(
            "default", order.getId(), "CLOSED"
        )).thenReturn(List.of());
        when(repository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, request.getId()
        )).thenReturn(allActs);
        when(serviceOrderRepository.findByTenantIdAndCompanyIdAndServiceRequestIdAndIsDeletedFalseOrderByCreatedAtDesc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, request.getId()
        )).thenReturn(List.of(order));
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", request.getId()))
            .thenReturn(List.of(acceptedExecution));
        when(repository.save(any(ServiceAcceptanceAct.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ServiceOrder readyOrder(UUID requestId) {
        ServiceOrder order = order(requestId);
        ServiceRequest request = request(requestId);
        stubOrder(order, request);
        when(executionRepository.findByTenantIdAndServiceRequestIdAndIsDeletedFalse("default", requestId))
            .thenReturn(List.of(execution(requestId, ServiceExecutionStatus.ACCEPTED)));
        return order;
    }

    private void stubOrder(ServiceOrder order, ServiceRequest request) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        when(serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", order.getId()))
            .thenReturn(Optional.of(order));
        lenient().when(serviceOrderRepository.findByTenantIdAndIdForAcceptanceUpdate("default", order.getId()))
            .thenReturn(Optional.of(order));
        when(serviceRequestRepository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        when(accessService.canAccessServiceOrder(order, request)).thenReturn(true);
    }

    private ServiceOrder order(UUID requestId) {
        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId("default");
        order.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        order.setCode("SO-CANONICAL");
        order.setName("Canonical order");
        order.setStatus("DRAFT");
        order.setServiceRequestId(requestId);
        order.setTotalAmount(BigDecimal.valueOf(100));
        return order;
    }

    private ServiceRequest request(UUID id) {
        ServiceRequest request = new ServiceRequest();
        request.setId(id);
        request.setTenantId("default");
        request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        request.setStatus("WAITING_ACCEPTANCE");
        return request;
    }

    private ServiceExecution execution(UUID requestId, ServiceExecutionStatus status) {
        ServiceExecution execution = new ServiceExecution();
        execution.setId(UUID.randomUUID());
        execution.setTenantId("default");
        execution.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        execution.setServiceRequestId(requestId);
        execution.setStatus(status.name());
        return execution;
    }

    private ServiceAcceptanceActCreateRequest validCreate(ServiceOrder order) {
        ServiceAcceptanceActCreateRequest create = new ServiceAcceptanceActCreateRequest();
        create.setCode("SAA-CANONICAL");
        create.setName("Canonical act");
        create.setServiceOrderId(order.getId());
        create.setServiceRequestId(order.getServiceRequestId());
        create.setAmount(BigDecimal.valueOf(100));
        create.setVatAmount(BigDecimal.ZERO);
        create.setVatRate(BigDecimal.ZERO);
        create.setStage("FINAL");
        create.setPeriodStart(LocalDate.of(2026, 1, 1));
        create.setPeriodEnd(LocalDate.of(2026, 1, 31));
        return create;
    }

    private ServiceAcceptanceAct stagedAct(
        ServiceOrder order,
        BigDecimal amount,
        String stage,
        LocalDate periodStart,
        LocalDate periodEnd
    ) {
        ServiceAcceptanceAct act = act("DRAFT", false);
        act.setServiceOrderId(order.getId());
        act.setServiceRequestId(order.getServiceRequestId());
        act.setAmount(amount);
        act.setStage(stage);
        act.setPeriodStart(periodStart);
        act.setPeriodEnd(periodEnd);
        return act;
    }

    private ServiceAcceptanceActService canonicalService() {
        return new ServiceAcceptanceActService(
            repository,
            new ServiceAcceptanceActMapper(),
            serviceOrderRepository,
            serviceRequestRepository,
            executionRepository,
            claimRepository,
            contractService,
            purchaseOrderRepository,
            purchaseOrderItemRepository,
            budgetItemService,
            attachmentService,
            integrationEventService,
            approvalEngineService,
            auditService,
            historyService,
            notificationService,
            roleDataScopeService,
            accessService,
            new ServiceOrderAcceptanceLifecycleService(repository, serviceOrderRepository),
            org.mockito.Mockito.mock(ProcurementPayablesService.class)
        );
    }

    private ServiceAcceptanceActService service() {
        return new ServiceAcceptanceActService(
            repository,
            mapper,
            serviceOrderRepository,
            serviceRequestRepository,
            executionRepository,
            claimRepository,
            contractService,
            purchaseOrderRepository,
            purchaseOrderItemRepository,
            budgetItemService,
            attachmentService,
            integrationEventService,
            approvalEngineService,
            auditService,
            historyService,
            notificationService,
            roleDataScopeService,
            accessService,
            orderLifecycleService,
            org.mockito.Mockito.mock(ProcurementPayablesService.class)
        );
    }
}
