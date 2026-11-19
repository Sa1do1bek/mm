package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.mapper.PurchaseOrderRevisionMapper;
import com.enterprise.mm.procurement.command.service.PurchaseOrderDispatchReadinessService;
import com.enterprise.mm.procurement.port.ProcurementStockPostingPort;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderRevisionRepository;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.supplier.po.service.SupplierPoCancellationService;
import com.enterprise.mm.supplier.po.service.SupplierPoCollaborationQueryService;
import com.enterprise.mm.supplier.po.service.SupplierPoDispatchService;
import com.enterprise.mm.supplier.po.service.SupplierPoSupersessionService;
import com.enterprise.mm.warehouse.receiving.service.WarehouseReceiptPostingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the new Phase B close() blocking rule: an unresolved supplier exception
 * (current response REVISION_REQUESTED) or an open confirmed commitment (no
 * fulfilling receipt) blocks close, distinct from the pre-existing close-blocking
 * reasons. Verification not run -- see final report.
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceSupplierCollaborationCloseTest {
    @Mock private PurchaseOrderRepository repository;
    @Mock private PurchaseOrderMapper mapper;
    @Mock private PurchaseOrderItemRepository itemRepository;
    @Mock private PurchaseOrderItemService itemService;
    @Mock private PurchaseOrderRevisionRepository revisionRepository;
    @Mock private PurchaseOrderRevisionMapper revisionMapper;
    @Mock private PurchaseOrderSourceAllocationRepository purchaseOrderSourceAllocationRepository;
    @Mock private PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Mock private ProcurementStockPostingPort stockPostingPort;
    @Mock private MaterialRepository materialRepository;
    @Mock private AdvanceShippingNoticeRepository asnRepository;
    @Mock private AdvanceShippingNoticeItemRepository asnItemRepository;
    @Mock private SupplierEvaluationService supplierEvaluationService;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private ContractService contractService;
    @Mock private ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    @Mock private WorkCompletionActRepository workCompletionActRepository;
    @Mock private ServiceClaimRepository serviceClaimRepository;
    @Mock private PurchaseRequestRepository purchaseRequestRepository;
    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private StorageBinRepository storageBinRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private PurchaseOrderDispatchReadinessService dispatchReadinessService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private WarehouseReceiptPostingService warehouseReceiptPostingService;
    @Mock private ProcurementLineageEnricher procurementLineageEnricher;
    @Mock private SupplierPoDispatchService supplierPoDispatchService;
    @Mock private SupplierPoSupersessionService supplierPoSupersessionService;
    @Mock private SupplierPoCancellationService supplierPoCancellationService;
    @Mock private SupplierPoCollaborationQueryService supplierPoCollaborationQueryService;
    @Mock private SupplierPoResponseRepository supplierPoResponseRepository;
    @Mock private SupplierPoCommitmentRepository supplierPoCommitmentRepository;
    @Mock private DeliveryScheduleRepository deliveryScheduleRepository;
    @Mock private ProcurementPayablesNotificationService payablesNotificationService;
    @Mock private com.enterprise.mm.repository.ProcurementProcedureRepository procurementProcedureRepository;
    @Mock private com.enterprise.mm.repository.ProcurementAwardOutcomeRepository procurementAwardOutcomeRepository;
    @Mock private BudgetItemService budgetItemService;
    @InjectMocks private PurchaseOrderService service;
    @Mock private com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService fakturaEvidence;

    private UUID purchaseOrderId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrderId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void closeBlockedByUnresolvedSupplierRevisionRequest() {
        PurchaseOrder po = purchaseOrder();
        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);

        SupplierPoResponse response = new SupplierPoResponse();
        response.setResponseStatus("REVISION_REQUESTED");
        when(supplierPoResponseRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId
        )).thenReturn(Optional.of(response));

        assertThatThrownBy(() -> service.close(purchaseOrderId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("unresolved supplier revision request");
    }

    @Test
    void closeBlockedByOpenConfirmedCommitmentWithoutFulfillingReceipt() {
        PurchaseOrder po = purchaseOrder();
        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(openMaterialLine(new BigDecimal("10"), BigDecimal.ZERO)));
        when(supplierPoResponseRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId
        )).thenReturn(Optional.empty());

        SupplierPoCommitment commitment = new SupplierPoCommitment();
        commitment.setId(UUID.randomUUID());
        commitment.setStatus("CONFIRMED");
        commitment.setDeliveryScheduleId(UUID.randomUUID());
        when(supplierPoCommitmentRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId, List.of("CONFIRMED")
        )).thenReturn(List.of(commitment));

        DeliverySchedule schedule = new DeliverySchedule();
        schedule.setPlannedQuantity(new BigDecimal("10"));
        schedule.setReceivedQuantity(new BigDecimal("4"));
        when(deliveryScheduleRepository.findByTenantIdAndCompanyIdAndSupplierPoCommitmentIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, commitment.getId()
        )).thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.close(purchaseOrderId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("confirmed supplier commitments that are not yet fulfilled");
    }

    @Test
    void closeAllowsConfirmedCommitmentWhenWarehouseReceiptAlreadyFulfilledPurchaseOrderLines() {
        PurchaseOrder po = purchaseOrder();
        po.setStatus(PurchaseOrderStatus.RECEIVED.name());
        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(openMaterialLine(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"))));
        when(supplierPoResponseRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId
        )).thenReturn(Optional.empty());

        service.close(purchaseOrderId);
    }

    @Test
    void closeRejectsPhysicallyReceivedMaterialUntilAcceptedFulfillmentExists() {
        PurchaseOrder po = purchaseOrder();
        po.setStatus(PurchaseOrderStatus.RECEIVED.name());
        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("default", purchaseOrderId))
            .thenReturn(List.of(openMaterialLine(new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO)));
        when(supplierPoResponseRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, purchaseOrderId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(purchaseOrderId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("accepted fulfillment");
    }

    private PurchaseOrderItem openMaterialLine(BigDecimal quantity, BigDecimal receivedQuantity) {
        return openMaterialLine(quantity, receivedQuantity, receivedQuantity);
    }

    private PurchaseOrderItem openMaterialLine(BigDecimal quantity, BigDecimal receivedQuantity, BigDecimal acceptedQuantity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setQuantity(quantity);
        item.setReceivedQuantity(receivedQuantity);
        item.setAcceptedQuantity(acceptedQuantity);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        return item;
    }

    private PurchaseOrder purchaseOrder() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(purchaseOrderId);
        po.setTenantId("default");
        po.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        po.setCode("PO-1");
        po.setStatus(PurchaseOrderStatus.SENT.name());
        po.setRequestType(RequestType.GOODS);
        return po;
    }
}
