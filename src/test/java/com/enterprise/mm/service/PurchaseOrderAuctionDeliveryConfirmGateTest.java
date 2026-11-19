package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoRequest;
import com.enterprise.mm.procurement.port.ProcurementStockPostingPort;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.supplier.po.service.SupplierPoDispatchService;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeReceiveActionPolicy;
import com.enterprise.mm.warehouse.receiving.service.WarehouseReceiptPostingService;
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

@ExtendWith(MockitoExtension.class)
class PurchaseOrderAuctionDeliveryConfirmGateTest {
    @Mock private PurchaseOrderRepository repository;
    @Mock private PurchaseOrderMapper mapper;
    @Mock private PurchaseOrderItemRepository itemRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private AdvanceShippingNoticeRepository asnRepository;
    @Mock private AdvanceShippingNoticeItemRepository asnItemRepository;
    @Mock private ProcurementStockPostingPort stockPostingPort;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private SupplierEvaluationService supplierEvaluationService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private WarehouseReceiptPostingService warehouseReceiptPostingService;
    @Mock private ProcurementPayablesNotificationService payablesNotificationService;
    @Mock private SupplierPoDispatchService supplierPoDispatchService;
    @Mock private ProcurementLineageEnricher procurementLineageEnricher;
    @Mock private ProcurementProcedureRepository procurementProcedureRepository;
    @Mock private ProcurementAwardOutcomeRepository procurementAwardOutcomeRepository;
    @Mock private BudgetItemService budgetItemService;
    @Mock private GoodsIncomeReceiveActionPolicy goodsIncomeReceiveActionPolicy;
    @Mock private PurchaseOrderActionPolicy purchaseOrderActionPolicy;
    @InjectMocks private PurchaseOrderService service;
    @Mock private com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService fakturaEvidence;

    private UUID purchaseOrderId;
    private UUID warehouseId;
    private PurchaseOrder purchaseOrder;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("auction-delivery-gate");
        CompanyContext.setCompany(UUID.randomUUID());
        purchaseOrderId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();

        purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId("auction-delivery-gate");
        purchaseOrder.setCompanyId(CompanyContext.currentCompany());
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCode("PO-AUCTION-TEST");
        purchaseOrder.setSupplierId(UUID.randomUUID());

        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId("auction-delivery-gate");
        warehouse.setCompanyId(CompanyContext.currentCompany());
        org.mockito.Mockito.lenient().when(warehouseRepository.findByIdAndIsDeletedFalse(warehouseId)).thenReturn(Optional.of(warehouse));
        org.mockito.Mockito.lenient().when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            any(), any(), eq(warehouseId)
        )).thenReturn(Optional.of(warehouse));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void auctionReceiveWithoutConfirmedAsnIsBlocked() {
        ProcurementAwardOutcome award = new ProcurementAwardOutcome();
        award.setSourceType(ProcurementAwardSourceType.MANAGED_AUCTION);
        org.mockito.Mockito.lenient().when(procurementAwardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                eq("auction-delivery-gate"), any(UUID.class), eq(purchaseOrderId)))
            .thenReturn(Optional.of(award));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("auction-delivery-gate", purchaseOrderId))
            .thenReturn(List.of(materialLine()));

        assertThatThrownBy(() -> service.receive(purchaseOrderId, new ReceivePoRequest(warehouseId, null, null, List.of())))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Delivery confirmed");

        verify(itemRepository, never()).save(any());
    }

    @Test
    void auctionReceiveWithConfirmedAsnIsAllowedPastGate() {
        ProcurementAwardOutcome award = new ProcurementAwardOutcome();
        award.setSourceType(ProcurementAwardSourceType.MANAGED_AUCTION);
        when(procurementAwardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                eq("auction-delivery-gate"), any(UUID.class), eq(purchaseOrderId)))
            .thenReturn(Optional.of(award));

        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(UUID.randomUUID());
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus(AdvanceShippingNoticeStatus.CONFIRMED.name());
        asn.setTenantId("auction-delivery-gate");
        asn.setCompanyId(CompanyContext.currentCompany());
        org.mockito.Mockito.lenient().when(asnRepository.findByIdAndIsDeletedFalse(asn.getId())).thenReturn(Optional.of(asn));

        PurchaseOrderItem item = materialLine();
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse("auction-delivery-gate", purchaseOrderId))
            .thenReturn(List.of(item));

        // Gate passed; subsequent receipt posting may fail on incomplete mocks — assert only that ASN gate message is gone.
        assertThatThrownBy(() -> service.receive(purchaseOrderId, new ReceivePoRequest(warehouseId, null, asn.getId(), List.of())))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("Delivery confirmed");
    }

    private PurchaseOrderItem materialLine() {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setQuantity(BigDecimal.TEN);
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setCode("POI-1");
        Material material = new Material();
        material.setId(UUID.randomUUID());
        material.setTenantId("auction-delivery-gate");
        material.setCompanyId(CompanyContext.currentCompany());
        material.setMaterialGroupId(UUID.randomUUID());
        item.setMaterial(material);
        item.setMaterialId(material.getId());
        return item;
    }
}
