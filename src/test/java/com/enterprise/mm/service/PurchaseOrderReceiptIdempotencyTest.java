package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.IntegrationEventStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.PurchaseOrderMapper;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoLineRequest;
import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoRequest;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.procurement.port.ProcurementStockPostingPort;
import com.enterprise.mm.procurement.port.ProcurementStockPostingPort.PostedReceipt;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.supplier.po.service.SupplierPoDispatchService;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingResult;
import com.enterprise.mm.warehouse.receiving.service.WarehouseReceiptPostingService;
import com.enterprise.mm.procurement.command.service.PurchaseOrderDispatchReadinessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderReceiptIdempotencyTest {
    @Mock private PurchaseOrderRepository repository;
    @Mock private PurchaseOrderMapper mapper;
    @Mock private PurchaseOrderItemRepository itemRepository;
    @Mock private ProcurementStockPostingPort stockPostingPort;
    @Mock private MaterialRepository materialRepository;
    @Mock private AdvanceShippingNoticeRepository asnRepository;
    @Mock private AdvanceShippingNoticeItemRepository asnItemRepository;
    @Mock private SupplierEvaluationService supplierEvaluationService;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private PurchaseOrderDispatchReadinessService dispatchReadinessService;
    @Mock private IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private WarehouseReceiptPostingService warehouseReceiptPostingService;
    @Mock private ProcurementPayablesNotificationService payablesNotificationService;
    @Mock private SupplierPoDispatchService supplierPoDispatchService;
    @Mock private ProcurementLineageEnricher procurementLineageEnricher;
    @Mock private ProcurementProcedureRepository procurementProcedureRepository;
    @Mock private ProcurementAwardOutcomeRepository procurementAwardOutcomeRepository;
    @Mock private SupplierPoResponseRepository supplierPoResponseRepository;
    @Mock private BudgetItemService budgetItemService;
    @Mock private com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeReceiveActionPolicy goodsIncomeReceiveActionPolicy;
    @Mock private PurchaseOrderActionPolicy purchaseOrderActionPolicy;
    @Mock private QualityInspectionService qualityInspectionService;
    @InjectMocks private PurchaseOrderService service;
    @Mock private com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService fakturaEvidence;

    private final AtomicReference<IdempotencyRecord> storedRecord = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("receipt-idempotency-test");
        CompanyContext.setCompany(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(idempotencyRecordRepository.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            anyString(), any(UUID.class), anyString(), any(UUID.class), anyString()
        )).thenAnswer(invocation -> Optional.ofNullable(storedRecord.get()));
        org.mockito.Mockito.lenient().when(idempotencyRecordRepository.save(any(IdempotencyRecord.class))).thenAnswer(invocation -> {
            IdempotencyRecord record = invocation.getArgument(0);
            storedRecord.set(record);
            return record;
        });
        ReflectionTestUtils.setField(
            service,
            "idempotencyService",
            new IdempotencyService(idempotencyRecordRepository, new ObjectMapper().findAndRegisterModules())
        );
        org.mockito.Mockito.lenient().when(procurementLineageEnricher.enrichPurchaseOrder(any(PurchaseOrderResponse.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient()
            .when(procurementAwardOutcomeRepository
                .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                    any(), any(), any()))
            .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient()
            .when(procurementProcedureRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient()
            .when(supplierPoResponseRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void repeatedReceiptKeyReturnsStoredOutcomeWithoutPostingAgain() {
        UUID purchaseOrderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        UUID asnId = UUID.randomUUID();
        UUID stockMovementId = UUID.randomUUID();
        long version = 7L;

        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId, version);
        PurchaseOrderItem item = item(purchaseOrderId, itemId, materialId);
        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId(TenantContext.currentTenant());
        warehouse.setCompanyId(CompanyContext.currentCompany());
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId
        )).thenReturn(Optional.of(warehouse));
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(storageLocationId);
        storageLocation.setTenantId(TenantContext.currentTenant());
        storageLocation.setCompanyId(CompanyContext.currentCompany());
        storageLocation.setWarehouseId(warehouseId);
        when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), storageLocationId
        )).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseOrderId
        )).thenReturn(List.of(item));
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId(TenantContext.currentTenant());
        asn.setCompanyId(CompanyContext.currentCompany());
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus("CONFIRMED");
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), asnId
        )).thenReturn(List.of(asnLine(asnId, itemId, materialId, "10")));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId))
            .thenReturn(Optional.of(item.getMaterial()));
        when(stockPostingPort.postAcceptedReceipt(any())).thenReturn(new PostedReceipt(stockMovementId, LocalDate.of(2026, 7, 15)));
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(mapper.toResponse(purchaseOrder)).thenAnswer(invocation -> response(purchaseOrder));
        when(integrationEventService.emit(
            anyString(), anyString(), eq(purchaseOrderId), ArgumentMatchers.<Map<String, ?>>any()
        )).thenAnswer(invocation -> integrationEvent(invocation.getArgument(0)));
        UUID goodsReceiptId = UUID.randomUUID();
        when(warehouseReceiptPostingService.record(any())).thenReturn(new ReceiptPostingResult(
            goodsReceiptId, "GR-IDEMP-1", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of()));

        ReceivePoRequest request = new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "LOT-2026-07",
                null,
                "ACCEPTED"
            )),
            null,
            version,
            List.of()
        );

        PurchaseOrderResponse first = service.receiveIdempotent(purchaseOrderId, request, "receipt-key-1");
        PurchaseOrderResponse replay = service.receiveIdempotent(purchaseOrderId, request, "receipt-key-1");

        assertThat(first.getReceiptOutcome()).isNotNull();
        assertThat(first.getReceiptOutcome().acceptedQuantity()).isEqualByComparingTo("10");
        assertThat(first.getReceiptOutcome().goodsReceiptId()).isEqualTo(goodsReceiptId);
        assertThat(first.getReceiptOutcome().stockMovements()).singleElement().satisfies(movement -> {
            assertThat(movement.stockMovementId()).isEqualTo(stockMovementId);
            assertThat(movement.purchaseOrderItemId()).isEqualTo(itemId);
        });
        assertThat(first.getReceiptOutcome().integrationEvents()).hasSize(4);
        assertThat(replay.getReceiptOutcome()).isEqualTo(first.getReceiptOutcome());
        verify(stockPostingPort, times(1)).postAcceptedReceipt(any());
        verify(warehouseReceiptPostingService, times(1)).record(any());
        verify(idempotencyRecordRepository, times(1)).save(any(IdempotencyRecord.class));
    }

    @Test
    void goodsReceiptRejectsFixedAssetLineSnapshotBeforeStockPosting() {
        UUID purchaseOrderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID storageLocationId = UUID.randomUUID();
        long version = 3L;

        PurchaseOrder purchaseOrder = purchaseOrder(purchaseOrderId, version);
        PurchaseOrderItem item = item(purchaseOrderId, itemId, materialId, MaterialType.FIXED_ASSET);
        when(repository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(repository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(roleDataScopeService.canAccessDocument(any(), any(), any())).thenReturn(true);
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setTenantId(TenantContext.currentTenant());
        warehouse.setCompanyId(CompanyContext.currentCompany());
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId
        )).thenReturn(Optional.of(warehouse));
        StorageLocation storageLocation = new StorageLocation();
        storageLocation.setId(storageLocationId);
        storageLocation.setTenantId(TenantContext.currentTenant());
        storageLocation.setCompanyId(CompanyContext.currentCompany());
        storageLocation.setWarehouseId(warehouseId);
        when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), storageLocationId
        )).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseOrderId
        )).thenReturn(List.of(item));
        ReceivePoRequest request = new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            null,
            List.of(new ReceivePoLineRequest(
                itemId,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "ASSET-LOT",
                null,
                "ACCEPTED"
            )),
            null,
            version,
            List.of()
        );

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.receive(purchaseOrderId, request)))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(((BusinessException) ex).getMessageKey())
                .isEqualTo("FIXED_ASSET_GOODS_RECEIPT_FORBIDDEN"));
        verify(stockPostingPort, org.mockito.Mockito.never()).postAcceptedReceipt(any());
        verify(warehouseReceiptPostingService, org.mockito.Mockito.never()).record(any());
    }

    private PurchaseOrder purchaseOrder(UUID id, long version) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(id);
        purchaseOrder.setTenantId(TenantContext.currentTenant());
        purchaseOrder.setCompanyId(CompanyContext.currentCompany());
        purchaseOrder.setCode("PO-IDEMP-1");
        purchaseOrder.setName("Idempotent receipt PO");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setSupplierId(UUID.randomUUID());
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCurrencyCode("UZS");
        purchaseOrder.setVersion(version);
        return purchaseOrder;
    }

    private PurchaseOrderItem item(UUID purchaseOrderId, UUID itemId, UUID materialId) {
        return item(purchaseOrderId, itemId, materialId, MaterialType.TMB);
    }

    private PurchaseOrderItem item(UUID purchaseOrderId, UUID itemId, UUID materialId, MaterialType materialType) {
        com.enterprise.mm.entity.Material material = new com.enterprise.mm.entity.Material();
        material.setId(materialId);
        material.setMaterialGroupId(UUID.randomUUID());
        material.setMaterialType(materialType);
        var unit = new com.enterprise.mm.entity.UnitOfMeasure();unit.setId(UUID.randomUUID());unit.setCode("EA");unit.setName("Each");unit.setStatus("ACTIVE");unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);material.setUnitOfMeasure(unit);material.setUnitOfMeasureId(unit.getId());

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(itemId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setName("Bearing");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialId);
        item.setMaterial(material);item.setUnit("EA");
        item.setMaterialTypeSnapshot(materialType);
        item.setQuantity(BigDecimal.TEN);
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(BigDecimal.valueOf(12));
        item.setStatus("OPEN");
        return item;
    }

    private PurchaseOrderResponse response(PurchaseOrder purchaseOrder) {
        PurchaseOrderResponse response = new PurchaseOrderResponse();
        response.setId(purchaseOrder.getId());
        response.setCode(purchaseOrder.getCode());
        response.setStatus(purchaseOrder.getStatus());
        response.setVersion(purchaseOrder.getVersion());
        return response;
    }

    private IntegrationEvent integrationEvent(String eventType) {
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setEventType(eventType);
        event.setStatus(IntegrationEventStatus.NEW.name());
        return event;
    }

    private AdvanceShippingNoticeItem asnLine(UUID asnId, UUID purchaseOrderItemId, UUID materialId, String quantity) {
        AdvanceShippingNoticeItem line = new AdvanceShippingNoticeItem();
        line.setId(UUID.randomUUID());
        line.setTenantId(TenantContext.currentTenant());
        line.setCompanyId(CompanyContext.currentCompany());
        line.setAsnId(asnId);
        line.setPurchaseOrderItemId(purchaseOrderItemId);
        line.setMaterialId(materialId);
        line.setQuantity(new BigDecimal(quantity));
        return line;
    }
}
