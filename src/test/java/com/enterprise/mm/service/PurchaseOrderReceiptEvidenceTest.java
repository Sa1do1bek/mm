package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.Attachment;
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
import com.enterprise.mm.payload.QualityInspectionCreateRequest;
import com.enterprise.mm.payload.QualityInspectionResponse;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.procurement.port.ProcurementStockPostingPort;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import com.enterprise.mm.repository.ProcurementProcedureRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.supplier.po.service.SupplierPoDispatchService;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeReceiveActionPolicy;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingResult;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingDraft;
import com.enterprise.mm.warehouse.receiving.service.WarehouseReceiptPostingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderReceiptEvidenceTest {
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
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private WarehouseReceiptPostingService warehouseReceiptPostingService;
    @Mock private ProcurementPayablesNotificationService payablesNotificationService;
    @Mock private SupplierPoDispatchService supplierPoDispatchService;
    @Mock private ProcurementLineageEnricher procurementLineageEnricher;
    @Mock private ProcurementProcedureRepository procurementProcedureRepository;
    @Mock private ProcurementAwardOutcomeRepository procurementAwardOutcomeRepository;
    @Mock private SupplierPoResponseRepository supplierPoResponseRepository;
    @Mock private SupplierPoCommitmentRepository supplierPoCommitmentRepository;
    @Mock private DeliveryScheduleRepository deliveryScheduleRepository;
    @Mock private BudgetItemService budgetItemService;
    @Mock private GoodsIncomeReceiveActionPolicy goodsIncomeReceiveActionPolicy;
    @Mock private PurchaseOrderActionPolicy purchaseOrderActionPolicy;
    @Mock private QualityInspectionService qualityInspectionService;
    @InjectMocks private PurchaseOrderService service;
    @Mock private com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService fakturaEvidence;

    private UUID purchaseOrderId;
    private UUID itemId;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID asnId;
    private UUID supplierId;
    private PurchaseOrder purchaseOrder;
    private PurchaseOrderItem item;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("receipt-evidence-test");
        CompanyContext.setCompany(UUID.randomUUID());
        purchaseOrderId = UUID.randomUUID();
        itemId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();
        storageLocationId = UUID.randomUUID();
        asnId = UUID.randomUUID();

        supplierId = UUID.randomUUID();
        purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId(TenantContext.currentTenant());
        purchaseOrder.setCompanyId(CompanyContext.currentCompany());
        purchaseOrder.setCode("PO-EVIDENCE-1");
        purchaseOrder.setName("Evidence receipt PO");
        purchaseOrder.setStatus(PurchaseOrderStatus.SENT.name());
        purchaseOrder.setSupplierId(supplierId);
        purchaseOrder.setRequestType(RequestType.GOODS);
        purchaseOrder.setCurrencyCode("UZS");
        purchaseOrder.setVersion(3L);

        com.enterprise.mm.entity.Material material = new com.enterprise.mm.entity.Material();
        material.setId(UUID.randomUUID());
        material.setMaterialGroupId(UUID.randomUUID());
        material.setMaterialType(MaterialType.TMB);
        var unit = new com.enterprise.mm.entity.UnitOfMeasure();
        unit.setId(UUID.randomUUID());
        unit.setCode("EA");
        unit.setName("Each");
        unit.setStatus("ACTIVE");
        unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);
        material.setUnitOfMeasure(unit);
        material.setUnitOfMeasureId(unit.getId());

        item = new PurchaseOrderItem();
        item.setId(itemId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setName("Damaged bearing");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setMaterialId(material.getId());
        item.setMaterial(material);
        item.setMaterialTypeSnapshot(MaterialType.TMB);
        item.setQuantity(BigDecimal.ONE);
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        item.setUnitPrice(BigDecimal.TEN);
        item.setUnit("EA");
        item.setStatus("OPEN");

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
        org.mockito.Mockito.lenient().when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), storageLocationId
        )).thenReturn(Optional.of(storageLocation));
        when(itemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseOrderId
        )).thenReturn(List.of(item));
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
    void exceptionReceiptWithoutEvidenceIsRejectedBeforeMutation() {
        stubConfirmedAsn();
        ReceivePoRequest request = exceptionRequest(List.of());

        assertThatThrownBy(() -> service.receive(purchaseOrderId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("evidence");

        verify(itemRepository, never()).save(any());
        verify(stockPostingPort, never()).postAcceptedReceipt(any());
    }

    @Test
    void evidenceFromAnotherBusinessDocumentIsRejected() {
        stubConfirmedAsn();
        UUID evidenceId = UUID.randomUUID();
        Attachment unrelated = evidence(evidenceId, UUID.randomUUID());
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), evidenceId
        )).thenReturn(Optional.of(unrelated));

        assertThatThrownBy(() -> service.receive(purchaseOrderId, exceptionRequest(List.of(evidenceId))))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not belong to purchase order");

        verify(itemRepository, never()).save(any());
    }

    @Test
    void validPurchaseOrderEvidencePermitsExceptionReceipt() {
        stubConfirmedAsnWithLine("1");
        UUID evidenceId = UUID.randomUUID();
        stubSuccessfulCompletion();
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), evidenceId
        )).thenReturn(Optional.of(evidence(evidenceId, purchaseOrderId)));

        PurchaseOrderResponse response = service.receive(purchaseOrderId, exceptionRequest(List.of(evidenceId)));

        assertThat(response.getReceiptOutcome().rejectedQuantity()).isEqualByComparingTo("1");
        verify(itemRepository).save(any(PurchaseOrderItem.class));
    }

    @Test
    void mixedReceiptPreservesExplicitAcceptedQuantityAndStockProvenance() {
        item.setQuantity(new BigDecimal("24"));
        stubConfirmedAsnWithLine("24");
        UUID evidenceId = UUID.randomUUID();
        stubSuccessfulCompletion();
        when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), evidenceId
        )).thenReturn(Optional.of(evidence(evidenceId, purchaseOrderId)));
        when(warehouseReceiptPostingService.record(any())).thenReturn(new ReceiptPostingResult(
            UUID.randomUUID(), "GR-MIXED-1", new BigDecimal("23"), BigDecimal.ONE, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        UUID inspectionId = UUID.randomUUID();
        QualityInspectionResponse createdInspection = qualityInspection(inspectionId, "QI-MIXED-1", "DRAFT");
        when(qualityInspectionService.create(any(QualityInspectionCreateRequest.class))).thenReturn(createdInspection);
        when(qualityInspectionService.startQuarantine(inspectionId))
            .thenReturn(qualityInspection(inspectionId, "QI-MIXED-1", "IN_QUARANTINE"));

        PurchaseOrderResponse response = service.receive(purchaseOrderId, new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId, null, BigDecimal.ONE, BigDecimal.ZERO,
                "One unit rejected at receiving", "LOT-MIXED", storageLocationId, null, null, new BigDecimal("23"))),
            null,
            3L,
            List.of(evidenceId)
        ));

        assertThat(item.getReceivedQuantity()).isEqualByComparingTo("24");
        // Receipt evidence accepts 23, but the PO line is not fulfilled until quarantine is released.
        assertThat(item.getAcceptedQuantity()).isEqualByComparingTo("0");
        assertThat(item.getRejectedQuantity()).isEqualByComparingTo("1");
        assertThat(response.getReceiptOutcome().acceptedQuantity()).isEqualByComparingTo("23");
        verify(stockPostingPort).postAcceptedReceipt(ArgumentMatchers.argThat(
            receipt -> receipt.quantity().compareTo(new BigDecimal("23")) == 0
                && receipt.sourceUnitPrice().compareTo(BigDecimal.TEN) == 0));

        org.mockito.ArgumentCaptor<ReceiptPostingDraft> postingCaptor =
            org.mockito.ArgumentCaptor.forClass(ReceiptPostingDraft.class);
        verify(warehouseReceiptPostingService).record(postingCaptor.capture());
        assertThat(postingCaptor.getValue().lines()).singleElement().satisfies(line -> {
            assertThat(line.physicalReceivedQuantity()).isEqualByComparingTo("24");
            assertThat(line.acceptedQuantity()).isEqualByComparingTo("23");
            assertThat(line.rejectedQuantity()).isEqualByComparingTo("1");
            assertThat(line.qualityStatus()).isEqualTo("PENDING_INSPECTION");
            assertThat(line.stockMovementId()).isNotNull();
        });
        org.mockito.ArgumentCaptor<QualityInspectionCreateRequest> inspectionCaptor =
            org.mockito.ArgumentCaptor.forClass(QualityInspectionCreateRequest.class);
        verify(qualityInspectionService).create(inspectionCaptor.capture());
        assertThat(inspectionCaptor.getValue().getPurchaseOrderId()).isEqualTo(purchaseOrderId);
        assertThat(inspectionCaptor.getValue().getPurchaseOrderItemId()).isEqualTo(itemId);
        assertThat(inspectionCaptor.getValue().getMaterialId()).isEqualTo(item.getMaterialId());
        assertThat(inspectionCaptor.getValue().getReceivedQuantity()).isEqualByComparingTo("23");
        assertThat(response.getReceiptOutcome().qualityInspections()).singleElement().satisfies(inspection -> {
            assertThat(inspection.qualityInspectionId()).isEqualTo(inspectionId);
            assertThat(inspection.purchaseOrderItemId()).isEqualTo(itemId);
            assertThat(inspection.quantity()).isEqualByComparingTo("23");
            assertThat(inspection.status()).isEqualTo("IN_QUARANTINE");
        });
    }

    @Test
    void materialReceiptRequiresConfirmedAsnBeforeMutation() {
        stubAuctionAward();
        ReceivePoRequest request = new ReceivePoRequest(
            warehouseId,
            null,
            null,
            List.of(new ReceivePoLineRequest(
                itemId,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null,
                null,
                "ACCEPTED"
            )),
            null,
            3L,
            List.of()
        );

        assertThatThrownBy(() -> service.receive(purchaseOrderId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("E-auction purchase order requires Delivery confirmed (ASN CONFIRMED) before goods receipt");

        verify(itemRepository, never()).save(any());
        verify(stockPostingPort, never()).postAcceptedReceipt(any());
    }

    @Test
    void materialReceiptRejectsUnconfirmedAsnBeforeMutation() {
        AdvanceShippingNotice asn = confirmedAsn();
        asn.setStatus("SENT");
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));

        assertThatThrownBy(() -> service.receive(purchaseOrderId, normalRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("ASN must be CONFIRMED before first purchase order receipt");

        verify(itemRepository, never()).save(any());
        verify(stockPostingPort, never()).postAcceptedReceipt(any());
    }

    @Test
    void materialReceiptErrorIncludesPurchaseOrderAndSupplierContext() {
        // UX-LIVE-01: a blocked receipt must still tell the FE which PO/supplier it was for.
        stubAuctionAward();
        ReceivePoRequest request = new ReceivePoRequest(
            warehouseId,
            null,
            null,
            List.of(new ReceivePoLineRequest(
                itemId, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, "ACCEPTED"
            )),
            null,
            3L,
            List.of()
        );

        assertThatThrownBy(() -> service.receive(purchaseOrderId, request))
            .isInstanceOf(BusinessException.class)
            .satisfies(exception -> {
                Map<String, Object> context = ((BusinessException) exception).getContext();
                assertThat(context).containsEntry("purchaseOrderId", purchaseOrderId);
                assertThat(context).containsEntry("purchaseOrderCode", "PO-EVIDENCE-1");
                assertThat(context).containsEntry("supplierId", supplierId);
            });
    }

    @Test
    void partialReceiptKeepsPurchaseOrderPartiallyReceivedAndTracksOpenQuantity() {
        // UX-LIVE-01: goods income context after a partial receive must keep reporting an
        // accurate previously-received/open quantity split for the remaining line.
        item.setQuantity(BigDecimal.TEN);
        item.setReceivedQuantity(BigDecimal.valueOf(4));
        stubConfirmedAsnWithLine("10");
        stubSuccessfulCompletion();

        ReceivePoRequest request = new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId, BigDecimal.valueOf(2), BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, "ACCEPTED"
            )),
            null,
            3L,
            List.of()
        );

        PurchaseOrderResponse response = service.receive(purchaseOrderId, request);

        assertThat(item.getReceivedQuantity()).isEqualByComparingTo("6");
        assertThat(purchaseOrder.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
        assertThat(response.getReceiptOutcome().acceptedQuantity()).isEqualByComparingTo("2");
        assertThat(response.getReceiptOutcome().purchaseOrderStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
    }

    private void stubSuccessfulCompletion() {
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), item.getMaterialId()
        )).thenReturn(Optional.of(item.getMaterial()));
        when(repository.save(purchaseOrder)).thenReturn(purchaseOrder);
        when(mapper.toResponse(purchaseOrder)).thenAnswer(invocation -> {
            PurchaseOrderResponse response = new PurchaseOrderResponse();
            response.setId(purchaseOrderId);
            response.setStatus(purchaseOrder.getStatus());
            response.setVersion(purchaseOrder.getVersion());
            return response;
        });
        when(procurementLineageEnricher.enrichPurchaseOrder(any(PurchaseOrderResponse.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(integrationEventService.emit(
            anyString(), anyString(), eq(purchaseOrderId), ArgumentMatchers.<Map<String, ?>>any()
        )).thenAnswer(invocation -> integrationEvent(invocation.getArgument(0)));
        when(warehouseReceiptPostingService.record(any())).thenReturn(new ReceiptPostingResult(
            UUID.randomUUID(), "GR-EVIDENCE-1", BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        org.mockito.Mockito.lenient().when(stockPostingPort.postAcceptedReceipt(any()))
            .thenReturn(new com.enterprise.mm.procurement.port.ProcurementStockPostingPort.PostedReceipt(
                UUID.randomUUID(), LocalDate.now()));
    }

    private void stubAuctionAward() {
        // Direct (non-auction) purchase orders do not require an ASN before receipt; only
        // auction-sourced awards do, so simulate a managed-auction award to exercise that path.
        com.enterprise.mm.entity.ProcurementAwardOutcome outcome = new com.enterprise.mm.entity.ProcurementAwardOutcome();
        outcome.setSourceType(com.enterprise.mm.enums.ProcurementAwardSourceType.MANAGED_AUCTION);
        org.mockito.Mockito.reset(procurementAwardOutcomeRepository);
        when(procurementAwardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                any(), any(), any()))
            .thenReturn(Optional.of(outcome));
    }

    private void stubConfirmedAsn() {
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(confirmedAsn()));
    }

    private void stubConfirmedAsnWithLine(String quantity) {
        stubConfirmedAsn();
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), asnId
        )).thenReturn(List.of(asnLine(quantity)));
    }

    private AdvanceShippingNotice confirmedAsn() {
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId(TenantContext.currentTenant());
        asn.setCompanyId(CompanyContext.currentCompany());
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setStatus("CONFIRMED");
        return asn;
    }

    private AdvanceShippingNoticeItem asnLine(String quantity) {
        AdvanceShippingNoticeItem line = new AdvanceShippingNoticeItem();
        line.setId(UUID.randomUUID());
        line.setTenantId(TenantContext.currentTenant());
        line.setCompanyId(CompanyContext.currentCompany());
        line.setAsnId(asnId);
        line.setPurchaseOrderItemId(itemId);
        line.setMaterialId(item.getMaterialId());
        line.setQuantity(new BigDecimal(quantity));
        return line;
    }

    private ReceivePoRequest normalRequest() {
        return new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null,
                null,
                "ACCEPTED"
            )),
            null,
            3L,
            List.of()
        );
    }

    private ReceivePoRequest exceptionRequest(List<UUID> evidenceIds) {
        return new ReceivePoRequest(
            warehouseId,
            storageLocationId,
            asnId,
            List.of(new ReceivePoLineRequest(
                itemId,
                BigDecimal.ZERO,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                "Rejected at receiving gate",
                null,
                null,
                "REJECTED"
            )),
            null,
            3L,
            evidenceIds
        );
    }

    private Attachment evidence(UUID id, UUID entityId) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setTenantId(TenantContext.currentTenant());
        attachment.setCompanyId(CompanyContext.currentCompany());
        attachment.setEntityType("PURCHASEORDER");
        attachment.setEntityId(entityId);
        attachment.setCode("ATT-EVIDENCE");
        attachment.setName("Damage evidence");
        attachment.setStatus("ACTIVE");
        return attachment;
    }

    private IntegrationEvent integrationEvent(String eventType) {
        IntegrationEvent event = new IntegrationEvent();
        event.setId(UUID.randomUUID());
        event.setEventType(eventType);
        event.setStatus(IntegrationEventStatus.NEW.name());
        return event;
    }

    private QualityInspectionResponse qualityInspection(UUID id, String number, String status) {
        QualityInspectionResponse response = new QualityInspectionResponse();
        response.setId(id);
        response.setInspectionNumber(number);
        response.setStatus(status);
        return response;
    }
}
