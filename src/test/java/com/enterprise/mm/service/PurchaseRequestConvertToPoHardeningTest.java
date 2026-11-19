package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.mapper.PurchaseRequestMapper;
import com.enterprise.mm.mapper.RFQMapper;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseRequestConvertToPoHardeningTest {
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
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private MaterialRepository materialRepository;
    @Mock private MaterialCostObservationService materialCostObservationService;
    @Mock private ProcurementLineageEnricher procurementLineageEnricher;
    @Mock private BudgetItemService budgetItemService;
    @Mock private PurchaseOrderDemandCyclePolicy purchaseOrderDemandCyclePolicy;
    @Mock private RfqAggregationService rfqAggregationService;
    @InjectMocks private PurchaseRequestService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void legacyConvertToPoShortcutRequiresProcurementMethodDecisionBeforeAnyReadOrWrite() {
        UUID purchaseRequestId = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();

        assertThatThrownBy(() -> service.convertToPo(purchaseRequestId, quotationId))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey")
            .isEqualTo("procurement.pmd.required");

        verifyNoInteractions(
            repository,
            quotationRepository,
            rfqRepository,
            quotationItemRepository,
            purchaseOrderRepository,
            purchaseOrderItemRepository,
            integrationEventService
        );
    }

    @Test
    void legacyCreateRfqShortcutStillRequiresProcurementMethodDecision() {
        UUID purchaseRequestId = UUID.randomUUID();

        assertThatThrownBy(() -> service.createRfq(purchaseRequestId))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey")
            .isEqualTo("procurement.pmd.required");

        verifyNoInteractions(repository, rfqRepository, itemRepository, integrationEventService);
    }
}
