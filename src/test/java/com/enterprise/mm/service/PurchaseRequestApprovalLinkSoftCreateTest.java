package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.mapper.PurchaseRequestMapper;
import com.enterprise.mm.mapper.RFQMapper;
import com.enterprise.mm.payload.PurchaseRequestCreateRequest;
import com.enterprise.mm.payload.PurchaseRequestResponse;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchaseRequestApprovalLinkSoftCreateTest {
    private static final String TENANT = "tenant-pr-soft-link";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000007713");

    private PurchaseRequestRepository repository;
    private ApprovalEngineService approvalEngineService;
    private PurchaseRequestService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        repository = mock(PurchaseRequestRepository.class);
        approvalEngineService = mock(ApprovalEngineService.class);
        PurchaseRequestMapper mapper = mock(PurchaseRequestMapper.class);
        ProcurementLineageEnricher procurementLineageEnricher = mock(ProcurementLineageEnricher.class);
        when(mapper.toEntity(any(PurchaseRequestCreateRequest.class))).thenAnswer(invocation -> {
            PurchaseRequestCreateRequest request = invocation.getArgument(0);
            PurchaseRequest entity = new PurchaseRequest();
            entity.setCode(request.getCode());
            entity.setName(request.getName());
            entity.setCompanyId(COMPANY_ID);
            entity.setRequestType(RequestType.GOODS);
            return entity;
        });
        when(mapper.toResponse(any(PurchaseRequest.class))).thenAnswer(invocation -> {
            PurchaseRequest entity = invocation.getArgument(0);
            PurchaseRequestResponse response = new PurchaseRequestResponse();
            response.setId(entity.getId());
            response.setCode(entity.getCode());
            response.setName(entity.getName());
            response.setStatus(entity.getStatus());
            return response;
        });
        when(repository.save(any(PurchaseRequest.class))).thenAnswer(invocation -> {
            PurchaseRequest entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setTenantId(TENANT);
            return entity;
        });
        when(procurementLineageEnricher.enrichPurchaseRequest(any(PurchaseRequestResponse.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        service = new PurchaseRequestService(
            repository,
            mapper,
            mock(PurchaseRequestItemRepository.class),
            mock(PurchaseRequestItemService.class),
            mock(RFQRepository.class),
            mock(RFQItemRepository.class),
            mock(RFQMapper.class),
            mock(SupplierQuotationRepository.class),
            mock(SupplierQuotationItemRepository.class),
            mock(PurchaseOrderRepository.class),
            mock(PurchaseOrderItemRepository.class),
            mock(PurchaseOrderSourceAllocationRepository.class),
            mock(PurchaseOrderMapper.class),
            mock(RFQPurchaseRequestSourceRepository.class),
            mock(IntegrationEventService.class),
            mock(AuditService.class),
            mock(RoleDataScopeService.class),
            approvalEngineService,
            mock(RfqAggregationService.class),
            mock(SupplierAccreditationService.class),
            mock(MaterialRepository.class),
            mock(MaterialCostObservationService.class),
            procurementLineageEnricher,
            mock(BudgetItemService.class),
            mock(PurchaseOrderDemandCyclePolicy.class),
            mock(PurchaseRequestActionPolicy.class)
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createSucceedsWithoutApprovalFlowAndDoesNotLink() {
        when(approvalEngineService.hasActiveFlow(
            eq(PurchaseRequestApprovalAdapter.MODULE_KEY),
            eq(COMPANY_ID),
            any(),
            eq(RequestType.GOODS)
        )).thenReturn(false);

        PurchaseRequestCreateRequest request = new PurchaseRequestCreateRequest();
        request.setCode("PR-SOFT-1");
        request.setName("Soft link PR");

        PurchaseRequestResponse created = service.create(request);

        assertThat(created.getCode()).isEqualTo("PR-SOFT-1");
        verify(approvalEngineService, never()).linkItem(any(), any(), any(), any());
    }

    @Test
    void createLinksApprovalWhenFlowExists() {
        when(approvalEngineService.hasActiveFlow(
            eq(PurchaseRequestApprovalAdapter.MODULE_KEY),
            eq(COMPANY_ID),
            any(),
            eq(RequestType.GOODS)
        )).thenReturn(true);

        PurchaseRequestCreateRequest request = new PurchaseRequestCreateRequest();
        request.setCode("PR-SOFT-2");
        request.setName("Linked PR");

        PurchaseRequestResponse created = service.create(request);

        verify(approvalEngineService).linkItem(
            eq(PurchaseRequestApprovalAdapter.MODULE_KEY),
            eq(created.getId()),
            eq(COMPANY_ID),
            eq("PR-SOFT-2")
        );
    }
}
