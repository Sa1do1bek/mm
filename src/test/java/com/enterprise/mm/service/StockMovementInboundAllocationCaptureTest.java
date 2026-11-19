package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.InventoryCostLayerType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationDraft;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationEvidence;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationWriter;
import com.enterprise.mm.inventory.allocation.StockMovementCostLineageDraft;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StockMovementAllocationRepository;
import com.enterprise.mm.repository.StockMovementAllocationCostLayerRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class StockMovementInboundAllocationCaptureTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID BIN = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID MOVEMENT_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID GENERATED_BATCH = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID BALANCE_ID = UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final UUID COST_LAYER = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID COST_MOVEMENT = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID UOM = UUID.fromString("b0000000-0000-0000-0000-000000000001");

    private final StockMovementRepository movementRepository = mock(StockMovementRepository.class);
    private final com.enterprise.mm.erp.ErpExchangeService erpExchange = mock(com.enterprise.mm.erp.ErpExchangeService.class);
    private final StockBalanceRepository balanceRepository = mock(StockBalanceRepository.class);
    private final StockBatchRepository batchRepository = mock(StockBatchRepository.class);
    private final MaterialRepository materialRepository = mock(MaterialRepository.class);
    private final MaterialCatalogGuard materialCatalogGuard = mock(MaterialCatalogGuard.class);
    private final InventoryCostingService costingService = mock(InventoryCostingService.class);
    private final StockMovementAllocationWriter allocationWriter = mock(StockMovementAllocationWriter.class);
    private final AuditService auditService = mock(AuditService.class);
    private final InventoryPeriodGuardService periodGuard = mock(InventoryPeriodGuardService.class);
    private final InventoryValuationPolicyService valuationPolicy = mock(InventoryValuationPolicyService.class);
    private final InventoryFreezeGuardService freezeGuard = mock(InventoryFreezeGuardService.class);
    private final StockMovementService service = new StockMovementService(
        movementRepository,
        new StockMovementMapper(),
        balanceRepository,
        batchRepository,
        materialRepository,
        materialCatalogGuard,
        mock(WarehouseRepository.class),
        new StockBalanceMutationService(),
        periodGuard,
        valuationPolicy,
        mock(StockValuationService.class),
        costingService,
        allocationWriter,
        mock(StockMovementAllocationRepository.class),
        mock(StockMovementAllocationCostLayerRepository.class),
        auditService,
        mock(RoleDataScopeService.class),
        freezeGuard,
        false
    );

    @BeforeEach
    void setUpContextAndMaterial() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        when(valuationPolicy.allowZeroCostReceipt()).thenReturn(false);
        when(materialRepository.findByIdAndIsDeletedFalse(MATERIAL_ID)).thenReturn(Optional.of(material()));
        when(materialCatalogGuard.requireActiveTmbMaterial(MATERIAL_ID, "materialId")).thenAnswer(invocation -> material());
        when(movementRepository.save(any(StockMovement.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void generatedBatchReceiptWritesExactTargetAllocationAndCreatedLineage() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "erpExchangeService", erpExchange);
        StockMovement movement = movement(MovementType.RECEIPT, null, "2.0000", "10.000000");
        movement.setVersion(3L);
        movement.setReferenceType(null);
        movement.setMovementDate(java.time.LocalDate.of(2025, 4, 9));
        StockBalance balance = balance(null, "5", "4", "1", "2", "50");
        stubPosting(movement, balance, null, "20.0000");

        service.post(MOVEMENT_ID);

        ArgumentCaptor<java.util.List<com.enterprise.mm.erp.ErpExchangeService.Line>> erpLines = ArgumentCaptor.forClass(java.util.List.class);
        verify(erpExchange).acceptWarehouseDocument(eq("RECEIPT"), eq(MOVEMENT_ID), eq(3L),
            eq(movement.getMovementDate()), eq("RECEIPT"), org.mockito.ArgumentMatchers.isNull(), eq(WAREHOUSE), erpLines.capture());
        assertThat(erpLines.getValue()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("2");
            assertThat(line.amount()).isEqualByComparingTo("20");
        });

        ArgumentCaptor<StockMovementAllocationEvidence> evidence =
            ArgumentCaptor.forClass(StockMovementAllocationEvidence.class);
        verify(allocationWriter).write(eq(movement), evidence.capture());
        StockMovementAllocationDraft allocation = evidence.getValue().allocations().getFirst();
        StockMovementCostLineageDraft lineage = allocation.costLineages().getFirst();

        assertThat(evidence.getValue().allocations()).hasSize(1);
        assertThat(allocation.allocationRole()).isEqualTo("TARGET");
        assertThat(allocation.balanceId()).isEqualTo(BALANCE_ID);
        assertThat(allocation.sourceStockKey()).isEqualTo(com.enterprise.mm.inventory.allocation.StockMovementStockKeySnapshot.empty());
        assertThat(allocation.targetStockKey().warehouseId()).isEqualTo(WAREHOUSE);
        assertThat(allocation.targetStockKey().storageLocationId()).isEqualTo(LOCATION);
        assertThat(allocation.targetStockKey().storageBinId()).isEqualTo(BIN);
        assertThat(allocation.targetStockKey().stockBatchId()).isEqualTo(GENERATED_BATCH);
        assertThat(allocation.targetStockKey().batchNumber()).isNotBlank();
        assertThat(allocation.targetStockKey().disposition()).isEqualTo("AVAILABLE");
        assertThat(allocation.onHandDelta()).isEqualByComparingTo("2");
        assertThat(allocation.availableDelta()).isEqualByComparingTo("2");
        assertThat(allocation.reservedDelta()).isZero();
        assertThat(allocation.quarantineDelta()).isZero();
        assertThat(allocation.totalValueDelta()).isEqualByComparingTo("20");
        assertThat(allocation.batchQuantityDelta()).isEqualByComparingTo("2");
        assertThat(allocation.uomIdSnapshot()).isEqualTo(UOM);
        assertThat(allocation.uomCodeSnapshot()).isEqualTo("PCS");
        assertThat(lineage.lineageRole()).isEqualTo("CREATED");
        assertThat(lineage.costLayerId()).isEqualTo(COST_LAYER);
        assertThat(lineage.sourceCostMovementId()).isEqualTo(COST_MOVEMENT);
        assertThat(lineage.quantityDelta()).isEqualByComparingTo("2");
        assertThat(lineage.valueDelta()).isEqualByComparingTo("20");
        assertThat(lineage.currencyCodeSnapshot()).isEqualTo("UZS");
        assertThat(lineage.exchangeRateSnapshot()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(movement.getStockBatchId()).isEqualTo(GENERATED_BATCH);
        assertThat(movement.getBatchNumberSnapshot()).isEqualTo(allocation.targetStockKey().batchNumber());
        assertThat(movement.getUomIdSnapshot()).isEqualTo(UOM);
        assertThat(movement.getUomCodeSnapshot()).isEqualTo("PCS");
        assertThat(movement.getSourceDispositionSnapshot()).isNull();
        assertThat(movement.getTargetDispositionSnapshot()).isEqualTo("AVAILABLE");

        ArgumentCaptor<StockBatch> generated = ArgumentCaptor.forClass(StockBatch.class);
        verify(batchRepository).save(generated.capture());
        assertThat(generated.getValue().getCompanyId()).isEqualTo(COMPANY);
        assertThat(generated.getValue().getQuantity()).isEqualByComparingTo("2");

        InOrder order = inOrder(allocationWriter, movementRepository, auditService);
        order.verify(allocationWriter).write(eq(movement), any());
        order.verify(movementRepository).save(movement);
        order.verify(auditService).log("StockMovement", MOVEMENT_ID, "POST", "RECEIPT", null, null);
    }

    @Test
    void existingBatchReturnCapturesActualBeforeAfterAndAvailableDisposition() {
        UUID existingBatchId = UUID.fromString("70000000-0000-0000-0000-000000000002");
        StockBatch existing = batch(existingBatchId, "RETURN-BATCH", "5");
        StockMovement movement = movement(MovementType.RETURN, existingBatchId, "2", "10");
        StockBalance balance = balance(existingBatchId, "5", "4", "1", "3", "50");
        stubPosting(movement, balance, existing, "20");

        service.post(MOVEMENT_ID);

        ArgumentCaptor<StockMovementAllocationEvidence> evidence =
            ArgumentCaptor.forClass(StockMovementAllocationEvidence.class);
        verify(allocationWriter).write(eq(movement), evidence.capture());
        StockMovementAllocationDraft allocation = evidence.getValue().allocations().getFirst();
        assertThat(allocation.onHandDelta()).isEqualByComparingTo("2");
        assertThat(allocation.availableDelta()).isEqualByComparingTo("2");
        assertThat(allocation.reservedDelta()).isZero();
        assertThat(allocation.quarantineDelta()).isZero();
        assertThat(allocation.totalValueDelta()).isEqualByComparingTo("20");
        assertThat(allocation.batchQuantityDelta()).isEqualByComparingTo("2");
        assertThat(allocation.targetStockKey().stockBatchId()).isEqualTo(existingBatchId);
        assertThat(allocation.targetStockKey().batchNumber()).isEqualTo("RETURN-BATCH");
        assertThat(allocation.targetStockKey().disposition()).isEqualTo("AVAILABLE");
        assertThat(existing.getQuantity()).isEqualByComparingTo("7");
    }

    @Test
    void productionReceiptKeepsAuthoritativeAllocationInsteadOfMultiplyingRoundedUnitCost() {
        StockMovement movement = movement(MovementType.RECEIPT, null, "3", "0.3333");
        movement.setReferenceType("PRODUCTION_OPERATION");
        movement.setTotalCost(new BigDecimal("1.0000"));
        StockBalance balance = balance(null, "0", "0", "0", "0", "0");
        stubPosting(movement, balance, null, "1.0000");
        when(costingService.recordProductionLayer(any(), any(), any())).thenReturn(
            new InventoryCostingService.InboundLayerResult(COST_LAYER, null, COST_MOVEMENT,
                new BigDecimal("3"), new BigDecimal("1.0000"), new BigDecimal("0.333333"), "UZS", BigDecimal.ONE));
        service.postProductionMovement(movement);
        assertThat(balance.getTotalValue()).isEqualByComparingTo("1.0000");
        assertThat(movement.getTotalCost()).isEqualByComparingTo("1.0000");
        verify(costingService).recordProductionLayer(eq(movement), eq(WAREHOUSE), eq(GENERATED_BATCH));
        verify(costingService, never()).recordInboundLayer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void positiveAdjustmentCarriesInventoryLineProvenanceAndAdjustmentCostLineage() {
        UUID inventoryItem = UUID.fromString("c0000000-0000-0000-0000-000000000001");
        StockMovement movement = movement(MovementType.ADJUSTMENT, null, "1", "12");
        movement.setSourceInventoryItemId(inventoryItem);
        StockBalance balance = balance(null, "3", "3", "0", "0", "36");
        stubPosting(movement, balance, null, "12");

        service.post(MOVEMENT_ID);

        ArgumentCaptor<StockMovementAllocationEvidence> evidence =
            ArgumentCaptor.forClass(StockMovementAllocationEvidence.class);
        verify(allocationWriter).write(eq(movement), evidence.capture());
        assertThat(evidence.getValue().sourceInventoryItemId()).isEqualTo(inventoryItem);
        assertThat(evidence.getValue().allocations().getFirst().costLineages().getFirst().lineageRole())
            .isEqualTo("CREATED");
        verify(costingService).recordInboundLayer(
            eq(movement), eq(WAREHOUSE), eq(GENERATED_BATCH), eq(new BigDecimal("1")),
            eq(new BigDecimal("12")), eq(InventoryCostLayerType.ADJUSTMENT_IN));
    }

    @Test
    void postedReplayDoesNotRepeatAnyPhysicalOrEvidenceMutation() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "erpExchangeService", erpExchange);
        StockMovement movement = movement(MovementType.RECEIPT, null, "2", "10");
        movement.setVersion(1L);
        movement.setReferenceType(null);
        movement.setMovementDate(java.time.LocalDate.of(2025, 4, 9));
        StockBalance balance = balance(null, "5", "5", "0", "0", "50");
        stubPosting(movement, balance, null, "20");

        service.post(MOVEMENT_ID);
        service.post(MOVEMENT_ID);

        verify(erpExchange, times(1)).acceptWarehouseDocument(anyString(), any(), org.mockito.ArgumentMatchers.anyLong(),
            any(), anyString(), any(), any(), any());

        verify(allocationWriter, times(1)).write(eq(movement), any());
        verify(costingService, times(1)).recordInboundLayer(any(), any(), any(), any(), any(), any());
        verify(batchRepository, times(1)).save(any(StockBatch.class));
        assertThat(balance.getQuantity()).isEqualByComparingTo("7");
        assertThat(movement.getStatus()).isEqualTo(StockMovementStatus.POSTED.name());
    }

    @Test
    void valueMismatchFailsBeforeEvidenceStatusAndAuditSoTransactionCanRollback() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "erpExchangeService", erpExchange);
        StockMovement movement = movement(MovementType.RECEIPT, null, "2", "10");
        StockBalance balance = balance(null, "5", "5", "0", "0", "50");
        stubPosting(movement, balance, null, "19.9999");

        assertThatThrownBy(() -> service.post(MOVEMENT_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("value", "cost");

        verify(allocationWriter, never()).write(any(), any());
        verify(movementRepository, never()).save(any(StockMovement.class));
        verify(auditService, never()).log(anyString(), any(), anyString(), anyString());
        assertThat(movement.getStatus()).isEqualTo(StockMovementStatus.DRAFT.name());
        org.mockito.Mockito.verifyNoInteractions(erpExchange);
    }

    @Test
    void documentOwnedLineDoesNotCreateASecondErpEvent() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "erpExchangeService", erpExchange);
        StockMovement movement = movement(MovementType.RECEIPT, null, "2", "10");
        movement.setReferenceType(null);
        movement.setDocumentId(UUID.randomUUID());
        StockBalance balance = balance(null, "5", "5", "0", "0", "50");
        stubPosting(movement, balance, null, "20");

        service.post(MOVEMENT_ID);

        assertThat(movement.getStatus()).isEqualTo(StockMovementStatus.POSTED.name());
        org.mockito.Mockito.verifyNoInteractions(erpExchange);
    }

    @Test
    void existingInboundBatchMustMatchTenantCompanyMaterialAndWarehouse() {
        StockMovement movement = movement(MovementType.RETURN, GENERATED_BATCH, "1", "10");
        StockBalance balance = balance(GENERATED_BATCH, "1", "1", "0", "0", "10");
        StockBatch invalid = batch(GENERATED_BATCH, "WRONG", "1");
        invalid.setCompanyId(UUID.randomUUID());
        stubPosting(movement, balance, invalid, "10");

        assertThatThrownBy(() -> service.post(MOVEMENT_ID))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("batch", "company");
        verify(costingService, never()).recordInboundLayer(any(), any(), any(), any(), any(), any());
        verify(allocationWriter, never()).write(any(), any());
    }

    @Test
    void approvalChangesOnlyBusinessStateWithoutPostingInventoryOrEvidence() {
        StockMovement movement = movement(MovementType.RECEIPT, null, "2", "10");
        movement.setStatus(StockMovementStatus.PENDING.name());

        service.approveDocumentLine(movement);

        assertThat(movement.getStatus()).isEqualTo(StockMovementStatus.APPROVED.name());
        assertThat(movement.getPostedAt()).isNull();
        verify(movementRepository).save(movement);
        verify(balanceRepository, never()).save(any(StockBalance.class));
        verify(batchRepository, never()).save(any(StockBatch.class));
        verify(costingService, never()).recordInboundLayer(any(), any(), any(), any(), any(), any());
        verify(allocationWriter, never()).write(any(), any());
    }

    @Test
    void postingAnApprovedLineAppliesInventoryAndEvidenceExactlyOnce() {
        StockMovement movement = movement(MovementType.RECEIPT, null, "2", "10");
        movement.setStatus(StockMovementStatus.APPROVED.name());
        StockBalance balance = balance(null, "5", "5", "0", "0", "50");
        stubPosting(movement, balance, null, "20");

        service.postApprovedDocumentLine(movement);
        service.postApprovedDocumentLine(movement);

        assertThat(movement.getStatus()).isEqualTo(StockMovementStatus.POSTED.name());
        assertThat(movement.getPostedAt()).isNotNull();
        assertThat(balance.getQuantity()).isEqualByComparingTo("7");
        verify(costingService, times(1)).recordInboundLayer(any(), any(), any(), any(), any(), any());
        verify(allocationWriter, times(1)).write(eq(movement), any());
    }

    @Test
    void manualReceiptPostsThroughValuationFreezePeriodAndAllocationServices() {
        Material valid = material();
        com.enterprise.mm.entity.UnitOfMeasure uom = new com.enterprise.mm.entity.UnitOfMeasure();
        uom.setId(UOM); uom.setCode("PCS"); uom.setStockQuantityScale(0);
        valid.setUnitOfMeasureId(UOM); valid.setUnitOfMeasure(uom);
        MaterialCatalogGuard catalog = mock(MaterialCatalogGuard.class);
        when(catalog.requireActiveTmbMaterial(MATERIAL_ID, "materialId")).thenReturn(valid);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "materialCatalogGuard", catalog);
        StockMovement movement = movement(MovementType.RECEIPT, null, "2", "10");
        movement.setReferenceType("MANUAL_WAREHOUSE");
        StockBalance balance = balance(null, "5", "4", "1", "2", "50");
        stubPosting(movement, balance, null, "20.0000");
        service.postManualWarehouseMovement(movement);
        assertThat(movement.getStatus()).isEqualTo("POSTED");
        assertThat(balance.getQuantity()).isEqualByComparingTo("7");
        verify(periodGuard).ensureOpen(eq(WAREHOUSE), any(), eq("stock movement"));
        verify(freezeGuard).ensureMovementAllowed(movement);
        verify(costingService).recordInboundLayer(any(), any(), any(), any(), any(), any());
        verify(allocationWriter).write(eq(movement), any());
    }

    @Test
    void genericCrudCannotForgeTheManagedManualWarehouseReference() {
        com.enterprise.mm.payload.StockMovementCreateRequest request = new com.enterprise.mm.payload.StockMovementCreateRequest();
        request.setReferenceType("MANUAL_WAREHOUSE");
        assertThatThrownBy(() -> service.create(request)).hasMessageContaining("scoped command");
        verify(movementRepository, never()).save(any());
    }

    private void stubPosting(
        StockMovement movement,
        StockBalance balance,
        StockBatch existingBatch,
        String costingValue
    ) {
        when(movementRepository.findByIdAndIsDeletedFalse(MOVEMENT_ID)).thenReturn(Optional.of(movement));
        AtomicReference<StockBatch> savedBatch = new AtomicReference<>(existingBatch);
        when(batchRepository.save(any(StockBatch.class))).thenAnswer(invocation -> {
            StockBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) {
                batch.setId(GENERATED_BATCH);
            }
            if (batch.getTenantId() == null) {
                batch.setTenantId(TENANT);
            }
            savedBatch.set(batch);
            return batch;
        });
        when(batchRepository.findByIdAndIsDeletedFalseForUpdate(any(UUID.class))).thenAnswer(invocation -> {
            StockBatch batch = savedBatch.get();
            return batch != null && batch.getId().equals(invocation.getArgument(0))
                ? Optional.of(batch)
                : Optional.empty();
        });
        when(balanceRepository.findCompanyBalanceForUpdate(
            anyString(), any(UUID.class), any(UUID.class), any(UUID.class),
            anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any()
        )).thenAnswer(invocation -> {
            balance.setStockBatchId(invocation.getArgument(9));
            return Optional.of(balance);
        });
        when(balanceRepository.save(any(StockBalance.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(costingService.recordInboundLayer(any(), any(), any(), any(), any(), any())).thenReturn(
            new InventoryCostingService.InboundLayerResult(
                COST_LAYER, null, COST_MOVEMENT, movement.getQuantity(), new BigDecimal(costingValue),
                movement.getUnitCost(), "UZS", BigDecimal.ONE
            )
        );
    }

    private StockMovement movement(MovementType type, UUID batchId, String quantity, String unitCost) {
        StockMovement movement = new StockMovement();
        movement.setId(MOVEMENT_ID);
        movement.setTenantId(TENANT);
        movement.setCompanyId(COMPANY);
        movement.setCode("MOV-1");
        movement.setName("Inbound movement");
        movement.setStatus(StockMovementStatus.DRAFT.name());
        movement.setMaterialId(MATERIAL_ID);
        movement.setWarehouseId(WAREHOUSE);
        movement.setStorageLocationId(LOCATION);
        movement.setStorageBinId(BIN);
        movement.setStockBatchId(batchId);
        movement.setMovementType(type);
        movement.setQuantity(new BigDecimal(quantity));
        movement.setUnitCost(new BigDecimal(unitCost));
        movement.setTotalCost(new BigDecimal(quantity).multiply(new BigDecimal(unitCost)));
        movement.setReferenceType("TEST");
        movement.setReferenceId(UUID.fromString("d0000000-0000-0000-0000-000000000001"));
        return movement;
    }

    private StockBalance balance(
        UUID batchId,
        String onHand,
        String available,
        String reserved,
        String quarantine,
        String totalValue
    ) {
        StockBalance balance = new StockBalance();
        balance.setId(BALANCE_ID);
        balance.setTenantId(TENANT);
        balance.setCompanyId(COMPANY);
        balance.setMaterialId(MATERIAL_ID);
        balance.setWarehouseId(WAREHOUSE);
        balance.setStorageLocationId(LOCATION);
        balance.setStorageBinId(BIN);
        balance.setStockBatchId(batchId);
        balance.setQuantity(new BigDecimal(onHand));
        balance.setAvailableQuantity(new BigDecimal(available));
        balance.setReservedQuantity(new BigDecimal(reserved));
        balance.setQuarantineQuantity(new BigDecimal(quarantine));
        balance.setTotalValue(new BigDecimal(totalValue));
        return balance;
    }

    private StockBatch batch(UUID id, String number, String quantity) {
        StockBatch batch = new StockBatch();
        batch.setId(id);
        batch.setTenantId(TENANT);
        batch.setCompanyId(COMPANY);
        batch.setMaterialId(MATERIAL_ID);
        batch.setWarehouseId(WAREHOUSE);
        batch.setBatchNumber(number);
        batch.setQuantity(new BigDecimal(quantity));
        batch.setPurchaseUnitCost(BigDecimal.ZERO);
        batch.setFinalUnitCost(BigDecimal.ZERO);
        batch.setAdditionalCostPerUnit(BigDecimal.ZERO);
        return batch;
    }

    private Material material() {
        UnitOfMeasure uom = new UnitOfMeasure();
        uom.setId(UOM);
        uom.setCode("PCS");
        Material material = new Material();
        material.setId(MATERIAL_ID);
        material.setTenantId(TENANT);
        material.setCompanyId(COMPANY);
        material.setUnitOfMeasureId(UOM);
        material.setUnitOfMeasure(uom);
        return material;
    }
}
