package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.enterprise.mm.entity.StockMovementAllocation;
import com.enterprise.mm.entity.StockMovementAllocationCostLayer;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationDraft;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationEvidence;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationWriter;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockMovementAllocationCostLayerRepository;
import com.enterprise.mm.repository.StockMovementAllocationRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StockMovementExactReversalTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID BATCH = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID BALANCE = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID ORIGINAL = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID REVERSAL = UUID.fromString("70000000-0000-0000-0000-000000000002");
    private static final UUID ALLOCATION = UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final UUID LAYER = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID ORIGINAL_COST_MOVEMENT = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID REVERSAL_COST_MOVEMENT = UUID.fromString("a0000000-0000-0000-0000-000000000002");
    private static final UUID UOM = UUID.fromString("b0000000-0000-0000-0000-000000000001");

    private final StockMovementRepository movementRepository = mock(StockMovementRepository.class);
    private final StockBalanceRepository balanceRepository = mock(StockBalanceRepository.class);
    private final StockBatchRepository batchRepository = mock(StockBatchRepository.class);
    private final MaterialRepository materialRepository = mock(MaterialRepository.class);
    private final InventoryCostingService costingService = mock(InventoryCostingService.class);
    private final StockMovementAllocationWriter allocationWriter = mock(StockMovementAllocationWriter.class);
    private final StockMovementAllocationRepository allocationRepository = mock(StockMovementAllocationRepository.class);
    private final StockMovementAllocationCostLayerRepository lineageRepository =
        mock(StockMovementAllocationCostLayerRepository.class);
    private final StockMovementService service = new StockMovementService(
        movementRepository,
        new StockMovementMapper(),
        balanceRepository,
        batchRepository,
        materialRepository,
        mock(MaterialCatalogGuard.class),
        mock(WarehouseRepository.class),
        new StockBalanceMutationService(),
        mock(InventoryPeriodGuardService.class),
        mock(InventoryValuationPolicyService.class),
        mock(StockValuationService.class),
        costingService,
        allocationWriter,
        allocationRepository,
        lineageRepository,
        mock(AuditService.class),
        mock(RoleDataScopeService.class),
        mock(InventoryFreezeGuardService.class),
        false
    );

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        when(materialRepository.findByIdAndIsDeletedFalse(MATERIAL)).thenReturn(Optional.of(material()));
        when(movementRepository.save(any(StockMovement.class))).thenAnswer(invocation -> {
            StockMovement movement = invocation.getArgument(0);
            if (movement.getId() == null) {
                movement.setId(REVERSAL);
            }
            return movement;
        });
        when(balanceRepository.save(any(StockBalance.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.save(any(StockBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void receiptCancellationInvertsPersistedAllocationAndCostLineageWithoutCurrentValuationRules() {
        StockMovement original = postedReceipt();
        StockMovementAllocation allocation = receiptAllocation();
        StockMovementAllocationCostLayer lineage = receiptLineage();
        StockBalance balance = balance();
        StockBatch batch = batch();
        when(movementRepository.findByIdAndIsDeletedFalse(ORIGINAL)).thenReturn(Optional.of(original));
        when(movementRepository.existsByTenantIdAndCompanyIdAndReversalOfIdAndIsDeletedFalse(
            TENANT, COMPANY, ORIGINAL)).thenReturn(false);
        when(allocationRepository.findByTenantIdAndCompanyIdAndMovementId(TENANT, COMPANY, ORIGINAL))
            .thenReturn(List.of(allocation));
        when(lineageRepository.findByTenantIdAndCompanyIdAndMovementId(TENANT, COMPANY, ORIGINAL))
            .thenReturn(List.of(lineage));
        when(balanceRepository.findByIdAndIsDeletedFalseForUpdate(BALANCE)).thenReturn(Optional.of(balance));
        when(batchRepository.findByIdAndIsDeletedFalseForUpdate(BATCH)).thenReturn(Optional.of(batch));
        when(costingService.reverseExactLineages(any(StockMovement.class), eq(WAREHOUSE), any(), eq("Wrong receipt")))
            .thenReturn(List.of(new InventoryCostingService.ExactReversalResult(
                LAYER, null, REVERSAL_COST_MOVEMENT,
                new BigDecimal("-2"), new BigDecimal("-20"), new BigDecimal("10"),
                "UZS", BigDecimal.ONE
            )));

        service.cancelPosted(ORIGINAL, null, "Wrong receipt");

        assertThat(balance.getQuantity()).isZero();
        assertThat(balance.getAvailableQuantity()).isZero();
        assertThat(balance.getTotalValue()).isZero();
        assertThat(batch.getQuantity()).isZero();
        assertThat(original.getStatus()).isEqualTo(StockMovementStatus.CANCELLED.name());

        ArgumentCaptor<StockMovementAllocationEvidence> evidenceCaptor =
            ArgumentCaptor.forClass(StockMovementAllocationEvidence.class);
        verify(allocationWriter).write(any(StockMovement.class), evidenceCaptor.capture());
        StockMovementAllocationDraft reversalAllocation = evidenceCaptor.getValue().allocations().getFirst();
        assertThat(reversalAllocation.allocationRole()).isEqualTo("SOURCE");
        assertThat(reversalAllocation.balanceId()).isEqualTo(BALANCE);
        assertThat(reversalAllocation.sourceStockKey().stockBatchId()).isEqualTo(BATCH);
        assertThat(reversalAllocation.onHandDelta()).isEqualByComparingTo("-2");
        assertThat(reversalAllocation.availableDelta()).isEqualByComparingTo("-2");
        assertThat(reversalAllocation.totalValueDelta()).isEqualByComparingTo("-20");
        assertThat(reversalAllocation.batchQuantityDelta()).isEqualByComparingTo("-2");
        assertThat(reversalAllocation.costLineages()).singleElement().satisfies(reversalLineage -> {
            assertThat(reversalLineage.lineageRole()).isEqualTo("CONSUMED");
            assertThat(reversalLineage.costLayerId()).isEqualTo(LAYER);
            assertThat(reversalLineage.sourceCostMovementId()).isEqualTo(REVERSAL_COST_MOVEMENT);
            assertThat(reversalLineage.quantityDelta()).isEqualByComparingTo("-2");
            assertThat(reversalLineage.valueDelta()).isEqualByComparingTo("-20");
        });
        verify(costingService, never()).consumeOutboundLayers(any(), any(), any(), any());
        verify(costingService, never()).recordInboundLayer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void contractReceiptCancellationPropagatesSpecificationAndCannotBeReversedAgain() {
        UUID specification = UUID.randomUUID();
        StockMovement original = postedReceipt();
        original.setReferenceType("MANUAL_WAREHOUSE");
        original.setContractSpecificationId(specification);
        StockBalance balance = balance();
        StockBatch batch = batch();
        when(movementRepository.findByIdAndIsDeletedFalse(ORIGINAL)).thenReturn(Optional.of(original));
        when(allocationRepository.findByTenantIdAndCompanyIdAndMovementId(TENANT, COMPANY, ORIGINAL))
            .thenReturn(List.of(receiptAllocation()));
        when(lineageRepository.findByTenantIdAndCompanyIdAndMovementId(TENANT, COMPANY, ORIGINAL))
            .thenReturn(List.of(receiptLineage()));
        when(balanceRepository.findByIdAndIsDeletedFalseForUpdate(BALANCE)).thenReturn(Optional.of(balance));
        when(batchRepository.findByIdAndIsDeletedFalseForUpdate(BATCH)).thenReturn(Optional.of(batch));

        var response = service.cancelPosted(ORIGINAL);
        ArgumentCaptor<StockMovement> saved = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepository, times(3)).save(saved.capture());
        StockMovement reversal = saved.getAllValues().getFirst();
        assertThat(response.getContractSpecificationId()).isEqualTo(specification);
        assertThat(reversal.getContractSpecificationId()).isEqualTo(specification);
        assertThat(reversal.getReversalOfId()).isEqualTo(ORIGINAL);
        assertThat(reversal.getStatus()).isEqualTo("POSTED");
        assertThat(balance.getQuantity()).isZero();
        assertThat(batch.getQuantity()).isZero();
        assertThat(original.getStatus()).isEqualTo("CANCELLED");

        // Represent the persisted inverse allocation so, without the guard, a second cancellation
        // would actually restore stock while the contract quantity query still excludes this chain.
        StockMovementAllocation inverse = receiptAllocation();
        inverse.setMovementId(REVERSAL);
        inverse.setAllocationRole("SOURCE");
        inverse.setSourceWarehouseIdSnapshot(WAREHOUSE);
        inverse.setSourceStorageLocationIdSnapshot(LOCATION);
        inverse.setSourceStockBatchIdSnapshot(BATCH);
        inverse.setOnHandDelta(new BigDecimal("-2"));
        inverse.setAvailableDelta(new BigDecimal("-2"));
        inverse.setTotalValueDelta(new BigDecimal("-20"));
        inverse.setBatchQuantityDelta(new BigDecimal("-2"));
        when(movementRepository.findByIdAndIsDeletedFalse(REVERSAL)).thenReturn(Optional.of(reversal));
        when(allocationRepository.findByTenantIdAndCompanyIdAndMovementId(TENANT, COMPANY, REVERSAL))
            .thenReturn(List.of(inverse));

        assertThatThrownBy(() -> service.cancelPosted(REVERSAL))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Contract-linked reversal cannot be cancelled");
        assertThat(balance.getQuantity()).isZero();
        assertThat(batch.getQuantity()).isZero();
        assertThat(original.getStatus()).isEqualTo("CANCELLED");
        assertThat(reversal.getStatus()).isEqualTo("POSTED");
        verify(movementRepository, times(3)).save(any(StockMovement.class));
        verify(allocationWriter, times(1)).write(any(), any());
    }

    @Test
    void refusesASecondActiveReversalBeforeCreatingOrMutatingAnything() {
        StockMovement original = postedReceipt();
        when(movementRepository.findByIdAndIsDeletedFalse(ORIGINAL)).thenReturn(Optional.of(original));
        when(movementRepository.existsByTenantIdAndCompanyIdAndReversalOfIdAndIsDeletedFalse(
            TENANT, COMPANY, ORIGINAL)).thenReturn(true);

        assertThatThrownBy(() -> service.cancelPosted(ORIGINAL, null, "Duplicate"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");

        verify(allocationRepository, never()).findByTenantIdAndCompanyIdAndMovementId(any(), any(), any());
        verify(allocationWriter, never()).write(any(), any());
    }

    @Test
    void refusesLegacyPostedMovementWithoutImmutableAllocationEvidence() {
        StockMovement original = postedReceipt();
        when(movementRepository.findByIdAndIsDeletedFalse(ORIGINAL)).thenReturn(Optional.of(original));
        when(movementRepository.existsByTenantIdAndCompanyIdAndReversalOfIdAndIsDeletedFalse(
            TENANT, COMPANY, ORIGINAL)).thenReturn(false);
        when(allocationRepository.findByTenantIdAndCompanyIdAndMovementId(TENANT, COMPANY, ORIGINAL))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.cancelPosted(ORIGINAL, null, "Legacy reversal"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("allocation evidence");

        verify(allocationWriter, never()).write(any(), any());
    }

    private StockMovement postedReceipt() {
        StockMovement movement = new StockMovement();
        movement.setId(ORIGINAL);
        movement.setTenantId(TENANT);
        movement.setCompanyId(COMPANY);
        movement.setCode("RECEIPT-1");
        movement.setName("Receipt");
        movement.setStatus(StockMovementStatus.POSTED.name());
        movement.setMaterialId(MATERIAL);
        movement.setWarehouseId(WAREHOUSE);
        movement.setStorageLocationId(LOCATION);
        movement.setStockBatchId(BATCH);
        movement.setMovementType(MovementType.RECEIPT);
        movement.setQuantity(new BigDecimal("2"));
        movement.setUnitCost(new BigDecimal("10"));
        movement.setTotalCost(new BigDecimal("20"));
        return movement;
    }

    private StockMovementAllocation receiptAllocation() {
        StockMovementAllocation allocation = new StockMovementAllocation();
        allocation.setId(ALLOCATION);
        allocation.setTenantId(TENANT);
        allocation.setCompanyId(COMPANY);
        allocation.setMovementId(ORIGINAL);
        allocation.setAllocationSequence(1);
        allocation.setAllocationRole("TARGET");
        allocation.setMaterialId(MATERIAL);
        allocation.setBalanceId(BALANCE);
        allocation.setTargetWarehouseIdSnapshot(WAREHOUSE);
        allocation.setTargetStorageLocationIdSnapshot(LOCATION);
        allocation.setTargetStockBatchIdSnapshot(BATCH);
        allocation.setTargetBatchNumberSnapshot("LOT-1");
        allocation.setTargetDispositionSnapshot("AVAILABLE");
        allocation.setUomIdSnapshot(UOM);
        allocation.setUomCodeSnapshot("PCS");
        allocation.setOnHandDelta(new BigDecimal("2"));
        allocation.setAvailableDelta(new BigDecimal("2"));
        allocation.setReservedDelta(BigDecimal.ZERO);
        allocation.setQuarantineDelta(BigDecimal.ZERO);
        allocation.setTotalValueDelta(new BigDecimal("20"));
        allocation.setBatchQuantityDelta(new BigDecimal("2"));
        allocation.setUnitCostSnapshot(new BigDecimal("10"));
        allocation.setCurrencyCodeSnapshot("UZS");
        allocation.setExchangeRateSnapshot(BigDecimal.ONE);
        return allocation;
    }

    private StockMovementAllocationCostLayer receiptLineage() {
        StockMovementAllocationCostLayer lineage = new StockMovementAllocationCostLayer();
        lineage.setTenantId(TENANT);
        lineage.setCompanyId(COMPANY);
        lineage.setAllocationId(ALLOCATION);
        lineage.setLineageSequence(1);
        lineage.setLineageRole("CREATED");
        lineage.setCostLayerId(LAYER);
        lineage.setSourceCostMovementId(ORIGINAL_COST_MOVEMENT);
        lineage.setQuantityDelta(new BigDecimal("2"));
        lineage.setValueDelta(new BigDecimal("20"));
        lineage.setUnitCostSnapshot(new BigDecimal("10"));
        lineage.setCurrencyCodeSnapshot("UZS");
        lineage.setExchangeRateSnapshot(BigDecimal.ONE);
        return lineage;
    }

    private StockBalance balance() {
        StockBalance balance = new StockBalance();
        balance.setId(BALANCE);
        balance.setTenantId(TENANT);
        balance.setCompanyId(COMPANY);
        balance.setMaterialId(MATERIAL);
        balance.setWarehouseId(WAREHOUSE);
        balance.setStorageLocationId(LOCATION);
        balance.setStockBatchId(BATCH);
        balance.setQuantity(new BigDecimal("2"));
        balance.setAvailableQuantity(new BigDecimal("2"));
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(new BigDecimal("20"));
        return balance;
    }

    private StockBatch batch() {
        StockBatch batch = new StockBatch();
        batch.setId(BATCH);
        batch.setTenantId(TENANT);
        batch.setCompanyId(COMPANY);
        batch.setMaterialId(MATERIAL);
        batch.setWarehouseId(WAREHOUSE);
        batch.setBatchNumber("LOT-1");
        batch.setQuantity(new BigDecimal("2"));
        return batch;
    }

    private Material material() {
        UnitOfMeasure uom = new UnitOfMeasure();
        uom.setId(UOM);
        uom.setCode("PCS");
        Material material = new Material();
        material.setId(MATERIAL);
        material.setTenantId(TENANT);
        material.setCompanyId(COMPANY);
        material.setUnitOfMeasureId(UOM);
        material.setUnitOfMeasure(uom);
        return material;
    }
}
