package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.AssetAcceptance;
import com.enterprise.mm.entity.AssetAcceptanceLine;
import com.enterprise.mm.entity.AssetAcceptanceUnit;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetManagementSettings;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.AssetAcceptanceApprovalStatus;
import com.enterprise.mm.enums.AssetAcceptancePostingStatus;
import com.enterprise.mm.enums.AssetAcceptanceUnitQualityDecision;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import com.enterprise.mm.payload.AssetAcceptanceCreateRequest;
import com.enterprise.mm.payload.AssetAcceptanceLineRequest;
import com.enterprise.mm.payload.AssetAcceptancePostRequest;
import com.enterprise.mm.payload.AssetAcceptanceQualityDecisionRequest;
import com.enterprise.mm.payload.AssetAcceptanceRestrictedReversalRequest;
import com.enterprise.mm.payload.AssetAcceptanceResponse;
import com.enterprise.mm.payload.AssetAcceptanceUnitRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.AssetAcceptanceLineRepository;
import com.enterprise.mm.repository.AssetAcceptanceRepository;
import com.enterprise.mm.repository.AssetAcceptanceUnitRepository;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.ContractInvoiceEvidenceSnapshotRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AssetAcceptanceOperationalFlowTest {
    @Mock private AssetAcceptanceRepository repository;
    @Mock private AssetAcceptanceLineRepository lineRepository;
    @Mock private AssetAcceptanceUnitRepository unitRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private FixedAssetRepository fixedAssetRepository;
    @Mock private AssetEventRepository assetEventRepository;
    @Mock private AssetManagementSettingsService settingsService;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ApprovalEngineService approvalEngineService;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ContractInvoiceEvidenceSnapshotRepository evidenceSnapshotRepository;
    @Mock private ContractPaymentRepository contractPaymentRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private ContractSpecificationRepository contractSpecificationRepository;
    @Mock private AdvanceShippingNoticeRepository asnRepository;
    @Mock private AdvanceShippingNoticeItemRepository asnItemRepository;
    @Mock private AssetIntegrationEventPublisher assetIntegrationEventPublisher;
    @Mock private MaterialCatalogGuard manualCatalog;
    @InjectMocks private AssetAcceptanceService service;

    private UUID companyId;
    private UUID purchaseOrderId;
    private UUID purchaseOrderItemId;
    private UUID materialId;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID receiverId;
    private UUID supplierId;
    private final List<AssetAcceptance> acceptances = new ArrayList<>();
    private final List<AssetAcceptanceLine> lines = new ArrayList<>();
    private final List<AssetAcceptanceUnit> units = new ArrayList<>();
    private final List<FixedAsset> fixedAssets = new ArrayList<>();
    private final List<AssetEvent> assetEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-acceptance-flow-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        purchaseOrderId = UUID.randomUUID();
        purchaseOrderItemId = UUID.randomUUID();
        materialId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();
        storageLocationId = UUID.randomUUID();
        receiverId = UUID.randomUUID();
        supplierId = UUID.randomUUID();

        service = new AssetAcceptanceService(
            repository,
            lineRepository,
            unitRepository,
            new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            purchaseOrderRepository,
            purchaseOrderItemRepository,
            fixedAssetRepository,
            assetEventRepository,
            settingsService,
            storageLocationRepository,
            employeeRepository,
            approvalEngineService,
            idempotencyService,
            evidenceSnapshotRepository,
            contractPaymentRepository,
            contractRepository,
            contractSpecificationRepository,
            org.mockito.Mockito.mock(com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService.class)
        );
        ReflectionTestUtils.setField(service, "fixedAssetsEnabled", true);
        ReflectionTestUtils.setField(service, "assetIntegrationEventPublisher", assetIntegrationEventPublisher);
        ReflectionTestUtils.setField(service, "asnRepository", asnRepository);
        ReflectionTestUtils.setField(service, "asnItemRepository", asnItemRepository);

        lenient().when(repository.save(any(AssetAcceptance.class))).thenAnswer(invocation -> {
            AssetAcceptance acceptance = assign(invocation.getArgument(0));
            acceptances.removeIf(existing -> existing.getId().equals(acceptance.getId()));
            acceptances.add(acceptance);
            return acceptance;
        });
        lenient().when(lineRepository.save(any(AssetAcceptanceLine.class))).thenAnswer(invocation -> {
            AssetAcceptanceLine line = assign(invocation.getArgument(0));
            lines.removeIf(existing -> existing.getId().equals(line.getId()));
            lines.add(line);
            return line;
        });
        lenient().when(unitRepository.save(any(AssetAcceptanceUnit.class))).thenAnswer(invocation -> {
            AssetAcceptanceUnit unit = assign(invocation.getArgument(0));
            units.removeIf(existing -> existing.getId().equals(unit.getId()));
            units.add(unit);
            return unit;
        });
        lenient().when(fixedAssetRepository.save(any(FixedAsset.class))).thenAnswer(invocation -> {
            FixedAsset asset = assign(invocation.getArgument(0));
            fixedAssets.removeIf(existing -> existing.getId().equals(asset.getId()));
            fixedAssets.add(asset);
            return asset;
        });
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            any(), any(), any()
        )).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(2);
            return fixedAssets.stream()
                .filter(asset -> id.equals(asset.getId()) && !asset.isDeleted())
                .findFirst();
        });
        lenient().when(evidenceSnapshotRepository.existsByTenantIdAndCompanyIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalse(
            any(), any(), any(), any()
        )).thenReturn(false);
        lenient().when(contractPaymentRepository.findByTenantIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalseOrderByCreatedAtDesc(
            any(), any(), any()
        )).thenReturn(List.of());
        lenient().when(assetEventRepository.save(any(AssetEvent.class))).thenAnswer(invocation -> {
            AssetEvent event = assign(invocation.getArgument(0));
            assetEvents.add(event);
            return event;
        });
        lenient().when(asnItemRepository.save(any(AdvanceShippingNoticeItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(asnRepository.save(any(AdvanceShippingNotice.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void manualAcceptanceCannotPostBeforeApprovalAndPostsWithoutProcurementAfterApproval() {
        AssetAcceptance acceptance = persistedAcceptance(1);
        acceptance.setPurchaseOrderId(null);
        UUID contractId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();
        acceptance.setContractId(contractId);
        lines.getFirst().setPurchaseOrderItemId(null);
        lines.getFirst().setContractSpecificationId(specId);
        units.getFirst().setPurchaseOrderItemId(null);
        acceptAll(acceptance);
        Contract contract = new Contract();
        contract.setId(contractId);
        contract.setTenantId(TenantContext.currentTenant());
        contract.setCompanyId(companyId);
        contract.setStatus("ACTIVE");
        ContractSpecification spec = new ContractSpecification();
        spec.setId(specId);
        spec.setContractId(contractId);
        spec.setTenantId(TenantContext.currentTenant());
        spec.setCompanyId(companyId);
        spec.setMaterialId(materialId);
        spec.setLineType(ProcurementLineType.MATERIAL);
        when(contractRepository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
        when(contractSpecificationRepository.findByIdAndIsDeletedFalse(specId)).thenReturn(Optional.of(spec));
        when(repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId, acceptance.getId()))
            .thenReturn(Optional.of(acceptance));
        when(lineRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId, acceptance.getId()))
            .thenReturn(List.of(lines.getFirst()));
        when(unitRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId, lines.getFirst().getId()))
            .thenReturn(units);
        assertThatThrownBy(() -> service.post(acceptance.getId(), new AssetAcceptancePostRequest(0L, false, "manual")))
            .hasMessageContaining("approved");
        approve(acceptance);
        ReflectionTestUtils.setField(service, "materialCatalogGuard", manualCatalog);
        com.enterprise.mm.entity.Material material = new com.enterprise.mm.entity.Material();
        material.setMaterialType(MaterialType.FIXED_ASSET);
        when(manualCatalog.requireActiveMaterial(materialId, "materialId")).thenReturn(material);
        AssetAcceptanceResponse response = service.post(acceptance.getId(), new AssetAcceptancePostRequest(0L, false, "manual"));
        assertThat(response.getPostingStatus()).isEqualTo("POSTED");
        assertThat(fixedAssets).hasSize(1);
        assertThat(fixedAssets.getFirst().getAcquisitionEvidenceId()).isEqualTo(acceptance.getId());
        verify(purchaseOrderRepository, never()).findByIdForUpdate(any());
        verify(purchaseOrderItemRepository, never()).save(any());
        assertThat(service.post(acceptance.getId(), new AssetAcceptancePostRequest(0L, false, "retry")).getPostingStatus()).isEqualTo("POSTED");
        assertThat(fixedAssets).hasSize(1);
    }

    @Test
    void cancelledReceiptCannotBeSubmittedOrPostedEvenIfPreviouslyApproved() {
        AssetAcceptance acceptance = persistedAcceptance(1);
        acceptance.setPurchaseOrderId(null);
        acceptance.setPostingStatus(AssetAcceptancePostingStatus.CANCELLED);
        when(repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), companyId, acceptance.getId()))
            .thenReturn(Optional.of(acceptance));
        assertThatThrownBy(() -> service.submit(acceptance.getId(), new VersionedAssetCommand(0L, "cancelled")))
            .hasMessageContaining("Cancelled");
        approve(acceptance);
        assertThatThrownBy(() -> service.post(acceptance.getId(), new AssetAcceptancePostRequest(0L, false, "cancelled")))
            .hasMessageContaining("Cancelled");
        assertThat(fixedAssets).isEmpty();
    }

    @Test
    void createRejectsFractionalAssetQuantityBeforeAnyReceiptStateChanges() {
        arrangeCentralWarehouse();
        arrangePurchaseOrder(item("5"));
        AssetAcceptanceCreateRequest request = createRequest("1.5", 2);

        assertThatThrownBy(() -> service.create(request))
            .satisfies(error -> assertThat(messageKey(error)).isEqualTo("ASSET_QUANTITY_MUST_BE_WHOLE"));

        assertThat(acceptances).isEmpty();
        assertThat(fixedAssets).isEmpty();
    }

    @Test
    void createRejectsWarehouseThatIsNotTheConfiguredCentralWarehouse() {
        arrangeCentralWarehouse();
        arrangePurchaseOrder(item("5"));
        UUID wrongWarehouseId = UUID.randomUUID();
        AssetAcceptanceCreateRequest request = new AssetAcceptanceCreateRequest(
            null, purchaseOrderId, null, null, "DEL-1", wrongWarehouseId, null, receiverId,
            LocalDate.of(2026, 9, 9), LocalDateTime.of(2026, 9, 9, 10, 0),
            "UZS", null, "delivery note", List.of(lineRequest("1", 1))
        );

        assertThatThrownBy(() -> service.create(request))
            .satisfies(error -> assertThat(messageKey(error)).isEqualTo("ASSET_SCOPE_DENIED"));
    }

    @Test
    void createRejectsContractThatDoesNotBelongToPurchaseOrder() {
        arrangeCentralWarehouse();
        PurchaseOrder order = purchaseOrder();
        order.setContractId(UUID.randomUUID());
        lenient().when(purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(order));
        lenient().when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseOrderId
        )).thenReturn(List.of(item("5")));

        AssetAcceptanceCreateRequest request = new AssetAcceptanceCreateRequest(
            UUID.randomUUID(), purchaseOrderId, null, null, "DEL-1", warehouseId, storageLocationId, receiverId,
            LocalDate.of(2026, 9, 9), LocalDateTime.of(2026, 9, 9, 10, 0),
            "UZS", null, "delivery note", List.of(lineRequest("1", 1))
        );

        assertThatThrownBy(() -> service.create(request))
            .satisfies(error -> assertThat(messageKey(error)).isEqualTo("ASSET_SCOPE_DENIED"));
    }

    @Test
    void qualityDecisionUpdatesUnitAndLineAggregatesWithoutCreatingAssets() {
        AssetAcceptance acceptance = persistedAcceptance(5);
        AssetAcceptanceLine line = lines.getFirst();
        List<AssetAcceptanceUnit> delivered = unitsFor(line);
        when(repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, acceptance.getId()
        )).thenReturn(Optional.of(acceptance));
        when(lineRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, acceptance.getId()
        )).thenReturn(List.of(line));
        when(unitRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, line.getId()
        )).thenReturn(delivered);
        when(repository.save(acceptance)).thenReturn(acceptance);

        AssetAcceptanceResponse response = service.recordQualityDecision(
            acceptance.getId(),
            new AssetAcceptanceQualityDecisionRequest(
                acceptance.getVersion(),
                List.of(
                    new AssetAcceptanceQualityDecisionRequest.UnitDecision(delivered.get(0).getId(), "ACCEPTED", "ok"),
                    new AssetAcceptanceQualityDecisionRequest.UnitDecision(delivered.get(1).getId(), "ACCEPTED", "ok"),
                    new AssetAcceptanceQualityDecisionRequest.UnitDecision(delivered.get(2).getId(), "ACCEPTED", "ok"),
                    new AssetAcceptanceQualityDecisionRequest.UnitDecision(delivered.get(3).getId(), "HOLD", "inspect"),
                    new AssetAcceptanceQualityDecisionRequest.UnitDecision(delivered.get(4).getId(), "REJECTED", "damaged")
                )
            )
        );

        assertThat(response.getLines()).singleElement().satisfies(row -> {
            assertThat(row.getAcceptedQuantity()).isEqualByComparingTo("3");
            assertThat(row.getHoldQuantity()).isEqualByComparingTo("1");
            assertThat(row.getRejectedQuantity()).isEqualByComparingTo("1");
            assertThat(row.getPendingQuantity()).isEqualByComparingTo("0");
        });
        assertThat(fixedAssets).isEmpty();
        assertThat(assetEvents).isEmpty();
    }

    @Test
    void approvalChangesOnlyApprovalStateAndCreatesNoAssets() {
        AssetAcceptance acceptance = persistedAcceptance(3);
        acceptAll(acceptance);
        when(repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, acceptance.getId()
        )).thenReturn(Optional.of(acceptance));
        when(lineRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, acceptance.getId()
        )).thenReturn(List.of(lines.getFirst()));
        when(unitRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, lines.getFirst().getId()
        )).thenReturn(unitsFor(lines.getFirst()));
        when(repository.save(acceptance)).thenReturn(acceptance);
        when(approvalEngineService.start("ASSET_ACCEPTANCE", acceptance.getId()))
            .thenReturn(new ApprovalInstanceResponse(
                UUID.randomUUID(), null, null, "ASSET_ACCEPTANCE", acceptance.getId(), "Asset acceptance",
                ApprovalInstanceStatus.PENDING, 1, null, null, List.of()
            ));

        AssetAcceptanceResponse response = service.submit(acceptance.getId(), new VersionedAssetCommand(acceptance.getVersion(), "submit"));

        assertThat(response.getApprovalStatus()).isEqualTo("SUBMITTED");
        assertThat(response.getPostingStatus()).isEqualTo("NOT_POSTED");
        assertThat(fixedAssets).isEmpty();
        assertThat(assetEvents).isEmpty();
    }

    @Test
    void postingCreatesOneFixedAssetPerAcceptedUnitAndNoStockArtifacts() {
        AssetAcceptance acceptance = persistedAcceptance(5);
        acceptAll(acceptance);
        approve(acceptance);
        PurchaseOrderItem item = item("5");
        arrangePostLookup(acceptance, item);

        AssetAcceptanceResponse response = service.post(
            acceptance.getId(),
            new AssetAcceptancePostRequest(acceptance.getVersion(), false, "post assets")
        );

        assertThat(response.getPostingStatus()).isEqualTo("POSTED");
        assertThat(fixedAssets).hasSize(5);
        assertThat(fixedAssets).allSatisfy(asset -> {
            assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_WAREHOUSE);
            assertThat(asset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.WAREHOUSE);
            assertThat(asset.getWarehouseId()).isEqualTo(warehouseId);
            assertThat(asset.getStorageLocationId()).isEqualTo(storageLocationId);
            assertThat(asset.getAcquisitionEvidenceType()).isEqualTo("ASSET_ACCEPTANCE");
            assertThat(asset.getAcquisitionEvidenceId()).isEqualTo(acceptance.getId());
        });
        assertThat(assetEvents).hasSize(5);
        verify(assetIntegrationEventPublisher, times(5)).publish(
            any(FixedAsset.class),
            eq("ASSET_ACCEPTANCE_POSTED"),
            isNull(),
            isNull(),
            eq("ASSET_ACCEPTANCE"),
            eq(acceptance.getId()),
            eq(acceptance.getReceiptDate()),
            eq("system"),
            isNull()
        );
        assertThat(item.getReceivedQuantity()).isEqualByComparingTo("5");
        assertThat(item.getAcceptedQuantity()).isEqualByComparingTo("5");
        assertThat(item.getRejectedQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void postingAllocatesAcceptedValueAcrossAssetsAndReconcilesRoundingToSourceTotal() {
        AssetAcceptance acceptance = persistedAcceptance(3);
        AssetAcceptanceLine line = lines.getFirst();
        line.setAcceptedValue(new BigDecimal("10.0000"));
        acceptAll(acceptance);
        approve(acceptance);
        PurchaseOrderItem item = item("3");
        item.setUnitPrice(new BigDecimal("3.3333"));
        arrangePostLookup(acceptance, item);

        service.post(acceptance.getId(), new AssetAcceptancePostRequest(acceptance.getVersion(), false, "post rounded assets"));

        assertThat(fixedAssets).extracting(FixedAsset::getAcquisitionValue)
            .containsExactly(new BigDecimal("3.3333"), new BigDecimal("3.3333"), new BigDecimal("3.3334"));
        assertThat(fixedAssets.stream()
            .map(FixedAsset::getAcquisitionValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("10.0000");
        assertThat(fixedAssets).allSatisfy(asset -> {
            assertThat(asset.getAcquisitionCurrencyCode()).isEqualTo("UZS");
            assertThat(asset.getAcquisitionExchangeRate()).isEqualByComparingTo("1.000000");
            assertThat(asset.getAcquisitionEvidenceType()).isEqualTo("ASSET_ACCEPTANCE");
            assertThat(asset.getAcquisitionEvidenceId()).isEqualTo(acceptance.getId());
        });
    }

    @Test
    void postingWithHeldUnitIsRejectedUntilUnitIsResolved() {
        AssetAcceptance acceptance = persistedAcceptance(5);
        List<AssetAcceptanceUnit> delivered = unitsFor(lines.getFirst());
        for (int index = 0; index < delivered.size(); index++) {
            delivered.get(index).setQualityDecision(index < 3
                ? AssetAcceptanceUnitQualityDecision.ACCEPTED
                : index == 3 ? AssetAcceptanceUnitQualityDecision.HOLD : AssetAcceptanceUnitQualityDecision.REJECTED);
        }
        refreshAggregates(lines.getFirst(), delivered);
        approve(acceptance);
        PurchaseOrderItem item = item("5");
        arrangePostLookup(acceptance, item);

        assertThatThrownBy(() -> service.post(
            acceptance.getId(),
            new AssetAcceptancePostRequest(acceptance.getVersion(), false, "first post")
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_ACCEPTANCE_UNRESOLVED_UNITS"));

        assertThat(fixedAssets).isEmpty();
        assertThat(assetEvents).isEmpty();
        assertThat(item.getReceivedQuantity()).isEqualByComparingTo("0");
        assertThat(item.getAcceptedQuantity()).isEqualByComparingTo("0");
        assertThat(item.getRejectedQuantity()).isEqualByComparingTo("0");

        delivered.get(3).setQualityDecision(AssetAcceptanceUnitQualityDecision.ACCEPTED);
        refreshAggregates(lines.getFirst(), delivered);
        service.post(acceptance.getId(), new AssetAcceptancePostRequest(acceptance.getVersion(), false, "held accepted"));

        assertThat(fixedAssets).hasSize(4);
        assertThat(item.getReceivedQuantity()).isEqualByComparingTo("5");
        assertThat(item.getAcceptedQuantity()).isEqualByComparingTo("4");
        assertThat(item.getRejectedQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void duplicatePostRetryDoesNotDuplicateAssetsOrEvents() {
        AssetAcceptance acceptance = persistedAcceptance(2);
        acceptAll(acceptance);
        approve(acceptance);
        arrangePostLookup(acceptance, item("2"));

        service.post(acceptance.getId(), new AssetAcceptancePostRequest(acceptance.getVersion(), false, "first"));
        service.post(acceptance.getId(), new AssetAcceptancePostRequest(acceptance.getVersion(), false, "retry"));

        assertThat(fixedAssets).hasSize(2);
        assertThat(assetEvents).hasSize(2);
    }

    @Test
    void createRejectsDeliveredQuantityAboveOpenAcceptedObligation() {
        arrangeCentralWarehouse();
        PurchaseOrderItem item = item("5");
        item.setAcceptedQuantity(new BigDecimal("4"));
        arrangePurchaseOrder(item);

        assertThatThrownBy(() -> service.create(createRequest("2", 2)))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_ACCEPTANCE_OVER_RECEIPT"));
    }

    @Test
    void createRejectsAssetAcceptanceAboveRemainingAsnQuantity() {
        arrangeCentralWarehouse();
        PurchaseOrderItem item = item("5");
        arrangePurchaseOrder(item);
        UUID asnId = UUID.randomUUID();
        AdvanceShippingNotice asn = asn(asnId, AdvanceShippingNoticeStatus.CONFIRMED);
        AdvanceShippingNoticeItem asnItem = asnItem(asnId, item, "5", "0", "0");
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, asnId
        )).thenReturn(List.of(asnItem));
        when(lineRepository.sumDeliveredQuantityByAsnAndPurchaseOrderItem(
            TenantContext.currentTenant(), companyId, asnId, purchaseOrderItemId
        )).thenReturn(new BigDecimal("4"));

        assertThatThrownBy(() -> service.create(createRequest("2", 2, asnId)))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_ACCEPTANCE_ASN_OVER_RECEIPT"));

        assertThat(acceptances).isEmpty();
    }

    @Test
    void postingAppliesAsnReceiptProgressSeparatelyFromAcceptedObligation() {
        UUID asnId = UUID.randomUUID();
        AssetAcceptance acceptance = persistedAcceptance(5);
        acceptance.setAsnId(asnId);
        List<AssetAcceptanceUnit> delivered = unitsFor(lines.getFirst());
        for (int index = 0; index < delivered.size(); index++) {
            delivered.get(index).setQualityDecision(index < 3
                ? AssetAcceptanceUnitQualityDecision.ACCEPTED
                : AssetAcceptanceUnitQualityDecision.REJECTED);
        }
        refreshAggregates(lines.getFirst(), delivered);
        approve(acceptance);
        PurchaseOrderItem item = item("5");
        arrangePostLookup(acceptance, item);
        AdvanceShippingNotice asn = asn(asnId, AdvanceShippingNoticeStatus.CONFIRMED);
        AdvanceShippingNoticeItem asnItem = asnItem(asnId, item, "5", "0", "0");
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, asnId
        )).thenReturn(List.of(asnItem));
        when(lineRepository.sumDeliveredQuantityByAsnAndPurchaseOrderItem(
            TenantContext.currentTenant(), companyId, asnId, purchaseOrderItemId
        )).thenReturn(new BigDecimal("5"));

        service.post(acceptance.getId(), new AssetAcceptancePostRequest(acceptance.getVersion(), false, "post asn assets"));

        assertThat(asnItem.getReceivedQuantity()).isEqualByComparingTo("5");
        assertThat(asn.getStatus()).isEqualTo(AdvanceShippingNoticeStatus.RECEIVED.name());
        assertThat(item.getReceivedQuantity()).isEqualByComparingTo("5");
        assertThat(item.getAcceptedQuantity()).isEqualByComparingTo("3");
        assertThat(item.getRejectedQuantity()).isEqualByComparingTo("2");
        assertThat(fixedAssets).hasSize(3);
    }

    @Test
    void restrictedReversalCancelsCleanErroneousPostedAcceptanceAndReversesFulfillment() {
        AssetAcceptance acceptance = persistedAcceptance(1);
        acceptAll(acceptance);
        approve(acceptance);
        PurchaseOrderItem item = item("1");
        arrangePostLookup(acceptance, item);
        service.post(acceptance.getId(), new AssetAcceptancePostRequest(acceptance.getVersion(), false, "wrong PO"));
        AssetAcceptanceUnit unit = unitsFor(lines.getFirst()).getFirst();
        FixedAsset asset = fixedAssets.getFirst();

        AssetAcceptanceResponse response = service.restrictedReversal(
            acceptance.getId(),
            new AssetAcceptanceRestrictedReversalRequest(acceptance.getVersion(), "clean correction")
        );

        assertThat(response.getPostingStatus()).isEqualTo("CANCELLED");
        assertThat(unit.getPostedFixedAssetId()).isNull();
        assertThat(asset.isDeleted()).isTrue();
        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.CANCELLED);
        assertThat(item.getReceivedQuantity()).isEqualByComparingTo("0");
        assertThat(item.getAcceptedQuantity()).isEqualByComparingTo("0");
        assertThat(item.getRejectedQuantity()).isEqualByComparingTo("0");
        assertThat(assetEvents).extracting(AssetEvent::getEventType)
            .containsExactly("ASSET_ACCEPTANCE_POSTED", "ASSET_ACCEPTANCE_REVERSED");
        verify(assetIntegrationEventPublisher).publish(
            eq(asset),
            eq("ASSET_ACCEPTANCE_REVERSED"),
            eq(FixedAssetLifecycleStatus.IN_WAREHOUSE),
            eq(FixedAssetCustodyKind.WAREHOUSE),
            eq("ASSET_ACCEPTANCE"),
            eq(acceptance.getId()),
            eq(acceptance.getReceiptDate()),
            eq("system"),
            isNull()
        );
    }

    @Test
    void restrictedReversalBlocksInvoicedAssetAcceptanceAndPreservesOriginalEvidence() {
        AssetAcceptance acceptance = postedSingleAssetAcceptance();
        when(evidenceSnapshotRepository.existsByTenantIdAndCompanyIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, ProcurementSourceDocumentType.ASSET_ACCEPTANCE, acceptance.getId()
        )).thenReturn(true);

        assertThatThrownBy(() -> service.restrictedReversal(
            acceptance.getId(),
            new AssetAcceptanceRestrictedReversalRequest(acceptance.getVersion(), "after invoice")
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_REVERSAL_HAS_DEPENDENCIES"));

        assertThat(acceptance.getPostingStatus()).isEqualTo(AssetAcceptancePostingStatus.POSTED);
        assertThat(fixedAssets.getFirst().isDeleted()).isFalse();
        assertThat(assetEvents).extracting(AssetEvent::getEventType)
            .containsExactly("ASSET_ACCEPTANCE_POSTED");
    }

    @Test
    void restrictedReversalBlocksPaidAssetAcceptance() {
        AssetAcceptance acceptance = postedSingleAssetAcceptance();
        ContractPayment payment = new ContractPayment();
        payment.setId(UUID.randomUUID());
        when(contractPaymentRepository.findByTenantIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), ProcurementSourceDocumentType.ASSET_ACCEPTANCE.name(), acceptance.getId()
        )).thenReturn(List.of(payment));

        assertThatThrownBy(() -> service.restrictedReversal(
            acceptance.getId(),
            new AssetAcceptanceRestrictedReversalRequest(acceptance.getVersion(), "after payment")
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_REVERSAL_HAS_DEPENDENCIES"));
    }

    @Test
    void restrictedReversalBlocksTransferredAssetAcceptance() {
        AssetAcceptance acceptance = postedSingleAssetAcceptance();
        fixedAssets.getFirst().setActiveTransferId(UUID.randomUUID());

        assertThatThrownBy(() -> service.restrictedReversal(
            acceptance.getId(),
            new AssetAcceptanceRestrictedReversalRequest(acceptance.getVersion(), "in transit")
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_REVERSAL_HAS_DEPENDENCIES"));
    }

    @Test
    void restrictedReversalBlocksCommissionedAssetAcceptance() {
        AssetAcceptance acceptance = postedSingleAssetAcceptance();
        FixedAsset asset = fixedAssets.getFirst();
        asset.setInventoryNumber("FA-0001");
        asset.setFirstCommissionedOn(LocalDate.of(2026, 9, 10));
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_USE);
        asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);

        assertThatThrownBy(() -> service.restrictedReversal(
            acceptance.getId(),
            new AssetAcceptanceRestrictedReversalRequest(acceptance.getVersion(), "commissioned")
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_REVERSAL_HAS_DEPENDENCIES"));
    }

    @Test
    void restrictedReversalBlocksSupplierReturnedAssetAcceptance() {
        AssetAcceptance acceptance = postedSingleAssetAcceptance();
        FixedAsset asset = fixedAssets.getFirst();
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.RETURNED_TO_SUPPLIER);
        asset.setCustodyKind(FixedAssetCustodyKind.EXTERNAL);

        assertThatThrownBy(() -> service.restrictedReversal(
            acceptance.getId(),
            new AssetAcceptanceRestrictedReversalRequest(acceptance.getVersion(), "already returned")
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_REVERSAL_HAS_DEPENDENCIES"));
    }

    private void arrangeCentralWarehouse() {
        AssetManagementSettings settings = new AssetManagementSettings();
        settings.setCentralWarehouseId(warehouseId);
        settings.setCentralStorageLocationId(storageLocationId);
        lenient().when(settingsService.requireConfigured()).thenReturn(settings);
        StorageLocation location = new StorageLocation();
        location.setId(storageLocationId);
        location.setWarehouseId(warehouseId);
        location.setCompanyId(companyId);
        location.setTenantId(TenantContext.currentTenant());
        lenient().when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, storageLocationId
        )).thenReturn(Optional.of(location));
        Employee receiver = new Employee();
        receiver.setId(receiverId);
        receiver.setCompanyId(companyId);
        receiver.setTenantId(TenantContext.currentTenant());
        lenient().when(employeeRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, List.of(receiverId)
        )).thenReturn(List.of(receiver));
    }

    private void arrangePurchaseOrder(PurchaseOrderItem item) {
        PurchaseOrder order = purchaseOrder();
        lenient().when(purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(order));
        lenient().when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseOrderId
        )).thenReturn(List.of(item));
        lenient().when(lineRepository.sumDeliveredQuantityByPurchaseOrderItem(
            TenantContext.currentTenant(), companyId, purchaseOrderItemId
        )).thenReturn(BigDecimal.ZERO);
    }

    private void arrangePostLookup(AssetAcceptance acceptance, PurchaseOrderItem item) {
        when(repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, acceptance.getId()
        )).thenReturn(Optional.of(acceptance));
        when(lineRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, acceptance.getId()
        )).thenReturn(List.of(lines.getFirst()));
        when(unitRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, lines.getFirst().getId()
        )).thenReturn(unitsFor(lines.getFirst()));
        when(purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder()));
        when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseOrderId
        )).thenReturn(List.of(item));
        when(lineRepository.save(lines.getFirst())).thenReturn(lines.getFirst());
        when(repository.save(acceptance)).thenReturn(acceptance);
    }

    private AssetAcceptance postedSingleAssetAcceptance() {
        AssetAcceptance acceptance = persistedAcceptance(1);
        acceptAll(acceptance);
        approve(acceptance);
        arrangePostLookup(acceptance, item("1"));
        service.post(acceptance.getId(), new AssetAcceptancePostRequest(acceptance.getVersion(), false, "post assets"));
        return acceptance;
    }

    private AssetAcceptanceCreateRequest createRequest(String quantity, int unitCount) {
        return createRequest(quantity, unitCount, null);
    }

    private AssetAcceptanceCreateRequest createRequest(String quantity, int unitCount, UUID asnId) {
        return new AssetAcceptanceCreateRequest(
            null, purchaseOrderId, null, asnId, "DEL-1", warehouseId, storageLocationId, receiverId,
            LocalDate.of(2026, 9, 9), LocalDateTime.of(2026, 9, 9, 10, 0),
            "UZS", null, "delivery note", List.of(lineRequest(quantity, unitCount))
        );
    }

    private AssetAcceptanceLineRequest lineRequest(String quantity, int unitCount) {
        List<AssetAcceptanceUnitRequest> unitRequests = new ArrayList<>();
        for (int index = 1; index <= unitCount; index++) {
            unitRequests.add(new AssetAcceptanceUnitRequest(
                index,
                "MSN-" + index,
                "FA-RCV-TEST-" + index,
                "SN-" + index,
                "TAG-" + index,
                "NEW"
            ));
        }
        return new AssetAcceptanceLineRequest(
            purchaseOrderItemId, materialId, "FIXED_ASSET", "PCS", new BigDecimal(quantity), unitRequests
        );
    }

    private AssetAcceptance persistedAcceptance(int quantity) {
        AssetAcceptance acceptance = accepted(UUID.randomUUID());
        acceptances.add(acceptance);
        PurchaseOrderItem item = item(Integer.toString(quantity));
        AssetAcceptanceLine line = line(acceptance, item, BigDecimal.valueOf(quantity), quantity);
        lines.add(line);
        for (int index = 1; index <= quantity; index++) {
            units.add(unit(line, index));
        }
        return acceptance;
    }

    private AssetAcceptance accepted(UUID id) {
        AssetAcceptance acceptance = new AssetAcceptance();
        acceptance.setId(id);
        acceptance.setTenantId(TenantContext.currentTenant());
        acceptance.setCompanyId(companyId);
        acceptance.setCode("AA-" + id.toString().substring(0, 8));
        acceptance.setName("Asset acceptance");
        acceptance.setStatus("DRAFT");
        acceptance.setVersion(0L);
        acceptance.setPurchaseOrderId(purchaseOrderId);
        acceptance.setSupplierId(supplierId);
        acceptance.setWarehouseId(warehouseId);
        acceptance.setStorageLocationId(storageLocationId);
        acceptance.setReceivedByEmployeeId(receiverId);
        acceptance.setReceiptDate(LocalDate.of(2026, 9, 9));
        acceptance.setReceivedAt(LocalDateTime.of(2026, 9, 9, 10, 0));
        acceptance.setCurrencyCode("UZS");
        acceptance.setExchangeRate(BigDecimal.ONE);
        acceptance.setExchangeRateSource("PO");
        acceptance.setApprovalStatus(AssetAcceptanceApprovalStatus.DRAFT);
        acceptance.setPostingStatus(AssetAcceptancePostingStatus.NOT_POSTED);
        return acceptance;
    }

    private AssetAcceptanceLine line(AssetAcceptance acceptance, PurchaseOrderItem item, BigDecimal quantity, int unitsCount) {
        AssetAcceptanceLine line = new AssetAcceptanceLine();
        line.setId(UUID.randomUUID());
        line.setTenantId(TenantContext.currentTenant());
        line.setCompanyId(companyId);
        line.setCode("AAL-" + line.getId().toString().substring(0, 8));
        line.setName("Acceptance line");
        line.setVersion(0L);
        line.setAssetAcceptanceId(acceptance.getId());
        line.setPurchaseOrderItemId(item.getId());
        line.setMaterialId(item.getMaterialId());
        line.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
        line.setUnit("PCS");
        line.setDeliveredQuantity(quantity);
        line.setPendingQuantity(BigDecimal.valueOf(unitsCount));
        line.setAcceptedQuantity(BigDecimal.ZERO);
        line.setRejectedQuantity(BigDecimal.ZERO);
        line.setHoldQuantity(BigDecimal.ZERO);
        line.setAcceptedCurrencyCode("UZS");
        line.setAcceptedValue(item.getUnitPrice().multiply(quantity));
        return line;
    }

    private AssetAcceptanceUnit unit(AssetAcceptanceLine line, int sequence) {
        AssetAcceptanceUnit unit = new AssetAcceptanceUnit();
        unit.setId(UUID.randomUUID());
        unit.setTenantId(TenantContext.currentTenant());
        unit.setCompanyId(companyId);
        unit.setCode("AAU-" + sequence);
        unit.setName("Acceptance unit " + sequence);
        unit.setVersion(0L);
        unit.setAssetAcceptanceLineId(line.getId());
        unit.setPurchaseOrderItemId(line.getPurchaseOrderItemId());
        unit.setMaterialId(line.getMaterialId());
        unit.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
        unit.setLineSequence(sequence);
        String receivingUnitNumber = "FA-RCV-TEST-" + sequence;
        unit.setReceivingUnitNumber(receivingUnitNumber);
        unit.setManufacturerSerialNumber("MSN-" + sequence);
        unit.setSerialNumber("MSN-" + sequence);
        unit.setTemporaryTag(receivingUnitNumber);
        unit.setCondition("NEW");
        unit.setQualityDecision(AssetAcceptanceUnitQualityDecision.PENDING);
        return unit;
    }

    private AdvanceShippingNotice asn(UUID id, AdvanceShippingNoticeStatus status) {
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(id);
        asn.setTenantId(TenantContext.currentTenant());
        asn.setCompanyId(companyId);
        asn.setCode("ASN-" + id.toString().substring(0, 8));
        asn.setName("Asset shipment");
        asn.setStatus(status.name());
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setSupplierId(supplierId);
        asn.setVersion(0L);
        return asn;
    }

    private AdvanceShippingNoticeItem asnItem(
        UUID asnId,
        PurchaseOrderItem item,
        String quantity,
        String receivedQuantity,
        String cancelledQuantity
    ) {
        AdvanceShippingNoticeItem asnItem = new AdvanceShippingNoticeItem();
        asnItem.setId(UUID.randomUUID());
        asnItem.setTenantId(TenantContext.currentTenant());
        asnItem.setCompanyId(companyId);
        asnItem.setCode("ASN-LINE-" + asnItem.getId().toString().substring(0, 8));
        asnItem.setName("ASN line");
        asnItem.setStatus("ACTIVE");
        asnItem.setVersion(0L);
        asnItem.setAsnId(asnId);
        asnItem.setPurchaseOrderItemId(item.getId());
        asnItem.setMaterialId(item.getMaterialId());
        asnItem.setQuantity(new BigDecimal(quantity));
        asnItem.setReceivedQuantity(new BigDecimal(receivedQuantity));
        asnItem.setCancelledQuantity(new BigDecimal(cancelledQuantity));
        return asnItem;
    }

    private List<AssetAcceptanceUnit> unitsFor(AssetAcceptanceLine line) {
        return units.stream()
            .filter(unit -> line.getId().equals(unit.getAssetAcceptanceLineId()))
            .toList();
    }

    private void acceptAll(AssetAcceptance acceptance) {
        AssetAcceptanceLine line = lines.getFirst();
        List<AssetAcceptanceUnit> delivered = unitsFor(line);
        delivered.forEach(unit -> unit.setQualityDecision(AssetAcceptanceUnitQualityDecision.ACCEPTED));
        refreshAggregates(line, delivered);
    }

    private void approve(AssetAcceptance acceptance) {
        acceptance.setApprovalStatus(AssetAcceptanceApprovalStatus.APPROVED);
    }

    private void refreshAggregates(AssetAcceptanceLine line, List<AssetAcceptanceUnit> delivered) {
        line.setAcceptedQuantity(BigDecimal.valueOf(delivered.stream()
            .filter(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.ACCEPTED)
            .count()));
        line.setRejectedQuantity(BigDecimal.valueOf(delivered.stream()
            .filter(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.REJECTED)
            .count()));
        line.setHoldQuantity(BigDecimal.valueOf(delivered.stream()
            .filter(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.HOLD)
            .count()));
        line.setPendingQuantity(BigDecimal.valueOf(delivered.stream()
            .filter(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.PENDING)
            .count()));
    }

    private PurchaseOrder purchaseOrder() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(purchaseOrderId);
        order.setTenantId(TenantContext.currentTenant());
        order.setCompanyId(companyId);
        order.setCode("PO-ASSET");
        order.setName("Fixed asset PO");
        order.setStatus(PurchaseOrderStatus.SENT.name());
        order.setSupplierId(supplierId);
        order.setRequestType(RequestType.GOODS);
        order.setCurrencyCode("UZS");
        order.setExchangeRate(BigDecimal.ONE);
        order.setVersion(0L);
        return order;
    }

    private PurchaseOrderItem item(String quantity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(purchaseOrderItemId);
        item.setTenantId(TenantContext.currentTenant());
        item.setCompanyId(companyId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setName("Laptop asset");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialId);
        item.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit("PCS");
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setAcceptedQuantity(BigDecimal.ZERO);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        return item;
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T assign(T entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }
        if (entity.getTenantId() == null) {
            entity.setTenantId(TenantContext.currentTenant());
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(companyId);
        }
        if (entity.getVersion() == null) {
            entity.setVersion(0L);
        }
        return entity;
    }

    private String messageKey(Throwable error) {
        return error instanceof com.enterprise.mm.error.LocalizedException localized
            ? localized.getMessageKey()
            : null;
    }
}
