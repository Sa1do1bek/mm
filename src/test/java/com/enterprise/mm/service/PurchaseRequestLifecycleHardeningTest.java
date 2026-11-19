package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.mapper.PurchaseRequestMapper;
import com.enterprise.mm.mapper.RFQMapper;
import com.enterprise.mm.payload.PurchaseRequestCreateRequest;
import com.enterprise.mm.payload.PurchaseRequestUpdateRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.PurchaseRequestApprovalAdapter;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseRequestLifecycleHardeningTest {
    @Mock private PurchaseRequestRepository repository;
    @Mock private PurchaseRequestMapper mapper;
    @Mock private PurchaseRequestItemRepository itemRepository;
    @Mock private PurchaseRequestItemService itemService;
    @Mock private RFQRepository rfqRepository;
    @Mock private RFQItemRepository rfqItemRepository;
    @Mock private RFQMapper rfqMapper;
    @Mock private SupplierQuotationRepository quotationRepository;
    @Mock private SupplierQuotationItemRepository quotationItemRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    @Mock private PurchaseOrderMapper purchaseOrderMapper;
    @Mock private RFQPurchaseRequestSourceRepository rfqPurchaseRequestSourceRepository;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private RfqAggregationService rfqAggregationService;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private MaterialRepository materialRepository;
    @Mock private MaterialCostObservationService materialCostObservationService;
    @Mock private ProcurementLineageEnricher procurementLineageEnricher;
    @Mock private BudgetItemService budgetItemService;
    @Mock private PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;
    @Mock private PurchaseRequestActionPolicy purchaseRequestActionPolicy;
    @InjectMocks private PurchaseRequestService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createDefaultsCodeAndUsesReadableFallbackForMissingHumanName() {
        PurchaseRequest entity = new PurchaseRequest();

        service.beforeCreate(entity, new PurchaseRequestCreateRequest());

        assertThat(entity.getCode()).startsWith("PURCHASEREQUEST-");
        assertThat(entity.getName()).isEqualTo("Purchase request");
        assertThat(entity.getName()).isNotEqualTo(entity.getCode());
    }

    @Test
    void createKeepsHumanNameSeparateFromGeneratedCode() {
        PurchaseRequest entity = new PurchaseRequest();
        entity.setName("Chemical reagents for ammonia production");

        service.beforeCreate(entity, new PurchaseRequestCreateRequest());

        assertThat(entity.getCode()).startsWith("PURCHASEREQUEST-");
        assertThat(entity.getName()).isEqualTo("Chemical reagents for ammonia production");
    }

    @Test
    void updatePreservesDraftWorkflowStatus() {
        UUID id = UUID.randomUUID();
        PurchaseRequest entity = request(id, PurchaseRequestStatus.DRAFT);
        PurchaseRequestUpdateRequest update = new PurchaseRequestUpdateRequest();
        update.setName("Updated request");
        update.setStatus(PurchaseRequestStatus.APPROVED.name());

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            entity.setName(update.getName());
            entity.setStatus(update.getStatus());
            return null;
        }).when(mapper).updateEntity(entity, update);
        when(repository.save(entity)).thenReturn(entity);

        service.update(id, update);

        assertThat(entity.getName()).isEqualTo("Updated request");
        assertThat(entity.getStatus()).isEqualTo(PurchaseRequestStatus.DRAFT.name());
    }

    @Test
    void updateRejectsApprovedPurchaseRequest() {
        UUID id = UUID.randomUUID();
        PurchaseRequest entity = request(id, PurchaseRequestStatus.APPROVED);

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.update(id, new PurchaseRequestUpdateRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase request can be edited only in DRAFT or RETURNED status");
        verifyNoInteractions(mapper);
    }

    @Test
    void submitRejectsStandalonePurchaseRequestWithoutDemandPlanLineage() {
        UUID id = UUID.randomUUID();
        PurchaseRequest entity = request(id, PurchaseRequestStatus.DRAFT);

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.submit(id))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase request must originate from an approved demand plan before submission");

        verifyNoInteractions(approvalEngineService);
    }

    @Test
    void submitStartsApprovalForDemandPlanPurchaseRequest() {
        UUID id = UUID.randomUUID();
        PurchaseRequest entity = request(id, PurchaseRequestStatus.DRAFT);
        entity.setDemandPlanId(UUID.randomUUID());

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);

        service.submit(id);

        verify(approvalEngineService).start(PurchaseRequestApprovalAdapter.MODULE_KEY, id);
    }

    @Test
    void createRejectsDepartmentOutsideActorScope() {
        UUID departmentId = UUID.randomUUID();
        PurchaseRequestCreateRequest create = new PurchaseRequestCreateRequest();
        create.setDepartmentId(departmentId);
        PurchaseRequest entity = new PurchaseRequest();
        entity.setDepartmentId(departmentId);

        when(mapper.toEntity(create)).thenReturn(entity);
        doThrow(new BusinessException("Purchase request can be created or edited only in the user's permitted department"))
            .when(roleDataScopeService)
            .assertDepartmentDocumentMutationAllowed(departmentId, "Purchase request");

        assertThatThrownBy(() -> service.create(create))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase request can be created or edited only in the user's permitted department");

        verify(roleDataScopeService).assertDepartmentDocumentMutationAllowed(departmentId, "Purchase request");
        verifyNoInteractions(repository);
    }

    private PurchaseRequest request(UUID id, PurchaseRequestStatus status) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        PurchaseRequest entity = new PurchaseRequest();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entity.setStatus(status.name());
        return entity;
    }
}
