package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ServiceClaimStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ServiceClaimMapper;
import com.enterprise.mm.payload.ServiceClaimCreateRequest;
import com.enterprise.mm.payload.ServiceClaimResponse;
import com.enterprise.mm.payload.ServiceClaimUpdateRequest;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.repository.UserRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceClaimCanonicalInvariantTest {
    @Mock private ServiceClaimRepository repository;
    @Mock private ServiceRequestRepository requestRepository;
    @Mock private AuditService auditService;
    @Mock private ServiceWorkflowNotificationService notificationService;
    @Mock private ServiceReviewHistoryService historyService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ServiceWorkflowAccessService accessService;
    @Mock private AttachmentService attachmentService;
    @Mock private ServiceAcceptanceActRepository acceptanceActRepository;
    @Mock private ServiceOrderRepository serviceOrderRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private WorkCompletionActRepository workCompletionActRepository;
    @Mock private UserRepository userRepository;

    private ServiceClaimService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        service = new ServiceClaimService(
            repository,
            requestRepository,
            new ServiceClaimMapper(),
            auditService,
            notificationService,
            historyService,
            roleDataScopeService,
            accessService,
            attachmentService,
            acceptanceActRepository,
            serviceOrderRepository,
            purchaseOrderRepository,
            purchaseOrderItemRepository,
            workCompletionActRepository,
            userRepository
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createRejectsSupplierThatDoesNotBelongToServiceRequest() {
        UUID requestSupplierId = UUID.randomUUID();
        ServiceRequest request = request(requestSupplierId);
        existingRequest(request);
        ServiceClaimCreateRequest create = validCreate(request);
        create.setSupplierId(UUID.randomUUID());

        assertThatThrownBy(() -> service.create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("supplier");

        verify(repository, never()).save(any(ServiceClaim.class));
    }

    @Test
    void createRejectsNegativePenaltyAmount() {
        ServiceRequest request = request(UUID.randomUUID());
        existingRequest(request);
        ServiceClaimCreateRequest create = validCreate(request);
        create.setPenaltyAmount(BigDecimal.valueOf(-1));

        assertThatThrownBy(() -> service.create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("penaltyAmount");

        verify(repository, never()).save(any(ServiceClaim.class));
    }

    @Test
    void sentClaimCannotBeSoftDeleted() {
        ServiceRequest request = request(UUID.randomUUID());
        ServiceClaim claim = claim(request, ServiceClaimStatus.SENT, false);
        existingClaim(claim, request, false);

        assertThatThrownBy(() -> service.softDelete(claim.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CREATED");

        assertThat(claim.isDeleted()).isFalse();
    }

    @Test
    void sentClaimCannotBeRestored() {
        ServiceRequest request = request(UUID.randomUUID());
        ServiceClaim claim = claim(request, ServiceClaimStatus.SENT, true);
        existingClaim(claim, request, true);

        assertThatThrownBy(() -> service.restore(claim.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CREATED");

        assertThat(claim.isDeleted()).isTrue();
    }

    @Test
    void createRejectsAcceptanceActFromAnotherServiceRequest() {
        ServiceRequest request = request(UUID.randomUUID());
        existingRequest(request);
        ServiceAcceptanceAct unrelatedAct = new ServiceAcceptanceAct();
        unrelatedAct.setId(UUID.randomUUID());
        unrelatedAct.setTenantId("default");
        unrelatedAct.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        unrelatedAct.setServiceRequestId(UUID.randomUUID());
        when(acceptanceActRepository.findByIdAndIsDeletedFalse(unrelatedAct.getId())).thenReturn(Optional.of(unrelatedAct));
        ServiceClaimCreateRequest create = validCreate(request);
        create.setServiceActId(unrelatedAct.getId());

        assertThatThrownBy(() -> service.create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("service act");

        verify(repository, never()).save(any(ServiceClaim.class));
    }

    @Test
    void mapperPreservesClaimAssigneeAcrossCommandAndResponse() {
        UUID assigneeId = UUID.randomUUID();
        ServiceClaimCreateRequest create = new ServiceClaimCreateRequest();
        create.setAssignedTo(assigneeId);

        ServiceClaimMapper mapper = new ServiceClaimMapper();
        ServiceClaim claim = mapper.toEntity(create);
        ServiceClaimResponse response = mapper.toResponse(claim);

        assertThat(claim.getAssignedTo()).isEqualTo(assigneeId);
        assertThat(response.getAssignedTo()).isEqualTo(assigneeId);
    }

    @Test
    void createRequiresCanonicalClaimAssignee() {
        ServiceRequest request = request(UUID.randomUUID());
        existingRequest(request);
        ServiceClaimCreateRequest create = validCreate(request);
        create.setAssignedTo(null);

        assertThatThrownBy(() -> service.create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("assignedTo");

        verify(repository, never()).save(any(ServiceClaim.class));
    }

    @Test
    void createRejectsClaimAssigneeFromAnotherCompany() {
        ServiceRequest request = request(UUID.randomUUID());
        existingRequest(request);
        ServiceClaimCreateRequest create = validCreate(request);
        UUID assigneeId = UUID.randomUUID();
        create.setAssignedTo(assigneeId);
        User crossCompanyUser = new User();
        crossCompanyUser.setId(assigneeId);
        crossCompanyUser.setTenantId("default");
        crossCompanyUser.setCompanyId(UUID.randomUUID());
        crossCompanyUser.setStatus("ACTIVE");
        when(userRepository.findByIdAndIsDeletedFalse(assigneeId)).thenReturn(Optional.of(crossCompanyUser));

        assertThatThrownBy(() -> service.create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("assignedTo");

        verify(repository, never()).save(any(ServiceClaim.class));
    }

    @Test
    void genericUpdateRejectsClaimThatAlreadyEnteredResolution() {
        ServiceRequest request = request(UUID.randomUUID());
        ServiceClaim claim = claim(request, ServiceClaimStatus.UNDER_RESOLUTION, false);
        existingClaim(claim, request, false);

        assertThatThrownBy(() -> service.update(claim.getId(), validUpdate()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only CREATED");

        verify(repository, never()).save(any(ServiceClaim.class));
        verify(historyService, never()).recordClaim(any(ServiceClaim.class), any(), any());
        verify(auditService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void genericUpdateRecordsHistoryAndAuditForDraftEdit() {
        ServiceRequest request = request(UUID.randomUUID());
        ServiceClaim claim = claim(request, ServiceClaimStatus.CREATED, false);
        existingClaim(claim, request, false);
        ServiceClaimUpdateRequest update = validUpdate();
        User assignee = new User();
        assignee.setId(update.getAssignedTo());
        assignee.setTenantId("default");
        assignee.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        assignee.setEnabled(true);
        assignee.setStatus("ACTIVE");
        when(userRepository.findByIdAndIsDeletedFalse(update.getAssignedTo())).thenReturn(Optional.of(assignee));
        when(repository.save(claim)).thenReturn(claim);

        ServiceClaimResponse response = service.update(claim.getId(), update);

        assertThat(response.getDescription()).isEqualTo("Updated draft claim");
        assertThat(response.getPenaltyAmount()).isEqualByComparingTo("25");
        verify(historyService).recordClaim(claim, "UPDATED", "Updated draft claim");
        verify(auditService).log("ServiceClaim", claim.getId(), "UPDATE", "SCL-UPDATED", null, null);
    }

    @Test
    void requestlessPoServiceClaimDerivesOriginSupplierAndCompanyFromOrder() {
        UUID supplierId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = purchaseOrder(RequestType.SERVICE, supplierId);
        ServiceOrder order = new ServiceOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId("default");
        order.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        order.setPurchaseOrderId(purchaseOrder.getId());
        order.setSupplierId(supplierId);
        when(serviceOrderRepository.findByTenantIdAndIdAndIsDeletedFalse("default", order.getId()))
            .thenReturn(Optional.of(order));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrder.getId()))
            .thenReturn(Optional.of(purchaseOrder));
        when(accessService.canAccessServiceOrder(order, null)).thenReturn(true);
        activeAssignee();
        when(repository.save(any(ServiceClaim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ServiceClaimCreateRequest create = validOriginCreate();
        create.setServiceOrderId(order.getId());
        create.setSupplierId(UUID.randomUUID()); // client value must never win

        assertThatThrownBy(() -> service.create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("supplier");

        create.setSupplierId(null);
        ServiceClaimResponse response = service.create(create);
        assertThat(response.getOriginType()).isEqualTo("PO_SERVICE");
        assertThat(response.getServiceRequestId()).isNull();
        assertThat(response.getServiceOrderId()).isEqualTo(order.getId());
        assertThat(response.getSupplierId()).isEqualTo(supplierId);
    }

    @Test
    void poWorksClaimDerivesSupplierAndRejectsWorkActFromAnotherLine() {
        UUID supplierId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = purchaseOrder(RequestType.SERVICE, supplierId);
        PurchaseOrderItem item = worksItem(purchaseOrder);
        WorkCompletionAct unrelatedAct = new WorkCompletionAct();
        unrelatedAct.setId(UUID.randomUUID());
        unrelatedAct.setTenantId("default");
        unrelatedAct.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        unrelatedAct.setPurchaseOrderId(purchaseOrder.getId());
        unrelatedAct.setPurchaseOrderItemId(UUID.randomUUID());
        when(purchaseOrderItemRepository.findByIdAndIsDeletedFalse(item.getId())).thenReturn(Optional.of(item));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrder.getId())).thenReturn(Optional.of(purchaseOrder));
        when(workCompletionActRepository.findByIdAndIsDeletedFalse(unrelatedAct.getId())).thenReturn(Optional.of(unrelatedAct));

        ServiceClaimCreateRequest create = validOriginCreate();
        create.setPurchaseOrderItemId(item.getId());
        create.setWorkCompletionActId(unrelatedAct.getId());

        assertThatThrownBy(() -> service.create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("work completion act");
        verify(repository, never()).save(any(ServiceClaim.class));
    }

    @Test
    void createRejectsAmbiguousPrimaryOrigins() {
        ServiceClaimCreateRequest create = validOriginCreate();
        create.setServiceRequestId(UUID.randomUUID());
        create.setServiceOrderId(UUID.randomUUID());

        assertThatThrownBy(() -> service.create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("exactly one");
        verify(repository, never()).save(any(ServiceClaim.class));
    }

    private void existingRequest(ServiceRequest request) {
        when(requestRepository.findByIdAndIsDeletedFalse(request.getId())).thenReturn(Optional.of(request));
        lenient().when(accessService.canAccessServiceRequest(request)).thenReturn(true);
    }

    private void existingClaim(ServiceClaim claim, ServiceRequest request, boolean deleted) {
        if (deleted) {
            when(repository.findAnyById(claim.getId())).thenReturn(Optional.of(claim));
        } else {
            when(repository.findByIdAndIsDeletedFalse(claim.getId())).thenReturn(Optional.of(claim));
        }
        existingRequest(request);
        when(accessService.canAccessServiceClaim(claim, request, null, null)).thenReturn(true);
    }

    private ServiceRequest request(UUID supplierId) {
        ServiceRequest request = new ServiceRequest();
        request.setId(UUID.randomUUID());
        request.setTenantId("default");
        request.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        request.setStatus("IN_PROGRESS");
        request.setSupplierId(supplierId);
        return request;
    }

    private ServiceClaim claim(ServiceRequest request, ServiceClaimStatus status, boolean deleted) {
        ServiceClaim claim = new ServiceClaim();
        claim.setId(UUID.randomUUID());
        claim.setTenantId("default");
        claim.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        claim.setCode("SCL-CANONICAL");
        claim.setName("Canonical claim");
        claim.setStatus(status.name());
        claim.setServiceRequestId(request.getId());
        claim.setSupplierId(request.getSupplierId());
        claim.setClaimType("OTHER");
        claim.setSeverity("MEDIUM");
        if (deleted) {
            claim.softDelete();
        }
        return claim;
    }

    private ServiceClaimCreateRequest validCreate(ServiceRequest request) {
        ServiceClaimCreateRequest create = new ServiceClaimCreateRequest();
        create.setCode("SCL-CANONICAL");
        create.setName("Canonical claim");
        create.setServiceRequestId(request.getId());
        create.setSupplierId(request.getSupplierId());
        create.setClaimType("OTHER");
        create.setSeverity("MEDIUM");
        create.setPenaltyAmount(BigDecimal.ZERO);
        create.setCurrencyCode("UZS");
        create.setAssignedTo(UUID.randomUUID());
        return create;
    }

    private ServiceClaimCreateRequest validOriginCreate() {
        ServiceClaimCreateRequest create = new ServiceClaimCreateRequest();
        create.setName("Canonical multi-origin claim");
        create.setClaimType("QUALITY");
        create.setSeverity("HIGH");
        create.setPenaltyAmount(BigDecimal.ZERO);
        create.setCurrencyCode("UZS");
        create.setAssignedTo(UUID.randomUUID());
        return create;
    }

    private ServiceClaimUpdateRequest validUpdate() {
        ServiceClaimUpdateRequest update = new ServiceClaimUpdateRequest();
        update.setCode("SCL-UPDATED");
        update.setName("Updated claim");
        update.setDescription("Updated draft claim");
        update.setClaimType("QUALITY");
        update.setSeverity("HIGH");
        update.setAssignedTo(UUID.randomUUID());
        update.setPenaltyAmount(BigDecimal.valueOf(25));
        update.setCurrencyCode("UZS");
        return update;
    }

    private PurchaseOrder purchaseOrder(RequestType requestType, UUID supplierId) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId("default");
        order.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        order.setRequestType(requestType);
        order.setSupplierId(supplierId);
        return order;
    }

    private PurchaseOrderItem worksItem(PurchaseOrder order) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setPurchaseOrderId(order.getId());
        item.setLineType(ProcurementLineType.WORK);
        return item;
    }

    private void activeAssignee() {
        User assignee = new User();
        assignee.setId(UUID.randomUUID());
        assignee.setTenantId("default");
        assignee.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        assignee.setEnabled(true);
        assignee.setStatus("ACTIVE");
        when(userRepository.findByIdAndIsDeletedFalse(any(UUID.class))).thenReturn(Optional.of(assignee));
    }
}
