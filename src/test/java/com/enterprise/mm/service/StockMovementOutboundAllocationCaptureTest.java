package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationDraft;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationEvidence;
import com.enterprise.mm.inventory.allocation.StockMovementAllocationWriter;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.StockMovementAllocationRepository;
import com.enterprise.mm.repository.StockMovementAllocationCostLayerRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StockMovementOutboundAllocationCaptureTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID BIN = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID MOVEMENT = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID BATCH_1 = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID BATCH_2 = UUID.fromString("70000000-0000-0000-0000-000000000002");
    private static final UUID BALANCE_1 = UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final UUID BALANCE_2 = UUID.fromString("80000000-0000-0000-0000-000000000002");
    private static final UUID LAYER_1 = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID LAYER_2 = UUID.fromString("90000000-0000-0000-0000-000000000002");
    private static final UUID PARENT_1 = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID COST_MOVEMENT_1 = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID COST_MOVEMENT_2 = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    private static final UUID UOM = UUID.fromString("c0000000-0000-0000-0000-000000000001");

    private final StockMovementRepository movementRepository = mock(StockMovementRepository.class);
    private final com.enterprise.mm.erp.ErpExchangeService erpExchange = mock(com.enterprise.mm.erp.ErpExchangeService.class);
    private final StockBalanceRepository balanceRepository = mock(StockBalanceRepository.class);
    private final StockBatchRepository batchRepository = mock(StockBatchRepository.class);
    private final MaterialRepository materialRepository = mock(MaterialRepository.class);
    private final MaterialCatalogGuard materialCatalogGuard = mock(MaterialCatalogGuard.class);
    private final InventoryCostingService costingService = mock(InventoryCostingService.class);
    private final InventoryValuationPolicyService valuationPolicy = mock(InventoryValuationPolicyService.class);
    private final StockMovementAllocationWriter allocationWriter = mock(StockMovementAllocationWriter.class);
    private final StockMovementService service = new StockMovementService(
        movementRepository,
        new StockMovementMapper(),
        balanceRepository,
        batchRepository,
        materialRepository,
        materialCatalogGuard,
        mock(WarehouseRepository.class),
        new StockBalanceMutationService(),
        mock(InventoryPeriodGuardService.class),
        valuationPolicy,
        mock(StockValuationService.class),
        costingService,
        allocationWriter,
        mock(StockMovementAllocationRepository.class),
        mock(StockMovementAllocationCostLayerRepository.class),
        mock(AuditService.class),
        mock(RoleDataScopeService.class),
        mock(InventoryFreezeGuardService.class),
        false
    );

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        when(valuationPolicy.resolveMethod(MATERIAL, WAREHOUSE)).thenReturn(ValuationMethod.FIFO);
        when(valuationPolicy.allowNegativeStock()).thenReturn(false);
        when(valuationPolicy.roundingScale()).thenReturn(6);
        when(valuationPolicy.roundingMode()).thenReturn(RoundingMode.HALF_UP);
        when(materialRepository.findByIdAndIsDeletedFalse(MATERIAL)).thenReturn(Optional.of(material()));
        when(materialCatalogGuard.requireActiveTmbMaterial(MATERIAL, "materialId")).thenAnswer(invocation -> material());
        when(movementRepository.save(any(StockMovement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(balanceRepository.save(any(StockBalance.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchRepository.save(any(StockBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void issueAcrossTwoBatchesWritesOneExactSourceAllocationPerMutatedBalance() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "erpExchangeService", erpExchange);
        StockMovement movement = movement();
        movement.setVersion(4L);
        StockBalance firstBalance = balance(BALANCE_1, BATCH_1, "2", "9");
        StockBalance secondBalance = balance(BALANCE_2, BATCH_2, "5", "30");
        StockBatch firstBatch = batch(BATCH_1, "LOT-1", "2");
        StockBatch secondBatch = batch(BATCH_2, "LOT-2", "5");
        Map<UUID, StockBalance> balances = Map.of(BATCH_1, firstBalance, BATCH_2, secondBalance);
        Map<UUID, StockBatch> batches = Map.of(BATCH_1, firstBatch, BATCH_2, secondBatch);

        when(movementRepository.findByIdAndIsDeletedFalse(MOVEMENT)).thenReturn(Optional.of(movement));
        when(costingService.consumeOutboundLayers(movement, WAREHOUSE, new BigDecimal("4"), ValuationMethod.FIFO))
            .thenReturn(new InventoryCostingService.CostResult(
                new BigDecimal("5.250000"),
                new BigDecimal("21.0000"),
                List.of(
                    new InventoryCostingService.LayerConsumption(
                        LAYER_1, PARENT_1, BATCH_1, COST_MOVEMENT_1,
                        new BigDecimal("2"), new BigDecimal("4.500000"), new BigDecimal("9.0000"),
                        "EUR", new BigDecimal("1.250000")),
                    new InventoryCostingService.LayerConsumption(
                        LAYER_2, null, BATCH_2, COST_MOVEMENT_2,
                        new BigDecimal("2"), new BigDecimal("6.000000"), new BigDecimal("12.0000"),
                        "UZS", BigDecimal.ONE)
                )
            ));
        when(balanceRepository.findCompanyBalanceForUpdate(
            anyString(), eq(COMPANY), eq(MATERIAL), eq(WAREHOUSE),
            anyBoolean(), eq(LOCATION), anyBoolean(), eq(BIN), eq(false), any(UUID.class)
        )).thenAnswer(invocation -> Optional.ofNullable(balances.get(invocation.getArgument(9))));
        when(batchRepository.findByIdAndIsDeletedFalseForUpdate(any(UUID.class)))
            .thenAnswer(invocation -> Optional.ofNullable(batches.get(invocation.getArgument(0))));

        service.post(MOVEMENT);

        ArgumentCaptor<List<com.enterprise.mm.erp.ErpExchangeService.Line>> erpLines = ArgumentCaptor.forClass(List.class);
        verify(erpExchange).acceptWarehouseDocument(eq("ISSUE"), eq(MOVEMENT), eq(4L),
            eq(movement.getMovementDate()), eq("ISSUE"), eq(WAREHOUSE), org.mockito.ArgumentMatchers.isNull(), erpLines.capture());
        assertThat(erpLines.getValue()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("4");
            assertThat(line.amount()).isEqualByComparingTo("21");
        });

        ArgumentCaptor<StockMovementAllocationEvidence> evidenceCaptor =
            ArgumentCaptor.forClass(StockMovementAllocationEvidence.class);
        verify(allocationWriter).write(eq(movement), evidenceCaptor.capture());
        List<StockMovementAllocationDraft> allocations = evidenceCaptor.getValue().allocations();

        assertThat(allocations).hasSize(2);
        assertThat(allocations.get(0)).satisfies(allocation -> {
            assertThat(allocation.allocationRole()).isEqualTo("SOURCE");
            assertThat(allocation.balanceId()).isEqualTo(BALANCE_1);
            assertThat(allocation.sourceStockKey().stockBatchId()).isEqualTo(BATCH_1);
            assertThat(allocation.sourceStockKey().batchNumber()).isEqualTo("LOT-1");
            assertThat(allocation.sourceStockKey().disposition()).isEqualTo("AVAILABLE");
            assertThat(allocation.targetStockKey()).isEqualTo(com.enterprise.mm.inventory.allocation.StockMovementStockKeySnapshot.empty());
            assertThat(allocation.onHandDelta()).isEqualByComparingTo("-2");
            assertThat(allocation.availableDelta()).isEqualByComparingTo("-2");
            assertThat(allocation.totalValueDelta()).isEqualByComparingTo("-9");
            assertThat(allocation.batchQuantityDelta()).isEqualByComparingTo("-2");
            assertThat(allocation.costLineages()).singleElement().satisfies(lineage -> {
                assertThat(lineage.lineageRole()).isEqualTo("CONSUMED");
                assertThat(lineage.costLayerId()).isEqualTo(LAYER_1);
                assertThat(lineage.parentCostLayerIdSnapshot()).isEqualTo(PARENT_1);
                assertThat(lineage.sourceCostMovementId()).isEqualTo(COST_MOVEMENT_1);
                assertThat(lineage.quantityDelta()).isEqualByComparingTo("-2");
                assertThat(lineage.valueDelta()).isEqualByComparingTo("-9");
                assertThat(lineage.currencyCodeSnapshot()).isEqualTo("EUR");
            });
        });
        assertThat(allocations.get(1)).satisfies(allocation -> {
            assertThat(allocation.balanceId()).isEqualTo(BALANCE_2);
            assertThat(allocation.sourceStockKey().stockBatchId()).isEqualTo(BATCH_2);
            assertThat(allocation.onHandDelta()).isEqualByComparingTo("-2");
            assertThat(allocation.totalValueDelta()).isEqualByComparingTo("-12");
            assertThat(allocation.batchQuantityDelta()).isEqualByComparingTo("-2");
            assertThat(allocation.costLineages()).singleElement().satisfies(lineage ->
                assertThat(lineage.sourceCostMovementId()).isEqualTo(COST_MOVEMENT_2));
        });
        assertThat(firstBalance.getQuantity()).isZero();
        assertThat(firstBatch.getQuantity()).isZero();
        assertThat(secondBalance.getQuantity()).isEqualByComparingTo("3");
        assertThat(secondBatch.getQuantity()).isEqualByComparingTo("3");
        assertThat(movement.getUomIdSnapshot()).isEqualTo(UOM);
        assertThat(movement.getUomCodeSnapshot()).isEqualTo("PCS");
        assertThat(movement.getSourceDispositionSnapshot()).isEqualTo("AVAILABLE");
        assertThat(movement.getStatus()).isEqualTo(StockMovementStatus.POSTED.name());
    }

    private StockMovement movement() {
        StockMovement movement = new StockMovement();
        movement.setId(MOVEMENT);
        movement.setTenantId(TENANT);
        movement.setCompanyId(COMPANY);
        movement.setCode("ISSUE-1");
        movement.setName("Issue");
        movement.setStatus(StockMovementStatus.DRAFT.name());
        movement.setMaterialId(MATERIAL);
        movement.setWarehouseId(WAREHOUSE);
        movement.setStorageLocationId(LOCATION);
        movement.setStorageBinId(BIN);
        movement.setMovementType(MovementType.ISSUE);
        movement.setQuantity(new BigDecimal("4"));
        movement.setUnitCost(BigDecimal.ZERO);
        movement.setTotalCost(BigDecimal.ZERO);
        return movement;
    }

    private StockBalance balance(UUID id, UUID batchId, String quantity, String value) {
        StockBalance balance = new StockBalance();
        balance.setId(id);
        balance.setTenantId(TENANT);
        balance.setCompanyId(COMPANY);
        balance.setMaterialId(MATERIAL);
        balance.setWarehouseId(WAREHOUSE);
        balance.setStorageLocationId(LOCATION);
        balance.setStorageBinId(BIN);
        balance.setStockBatchId(batchId);
        balance.setQuantity(new BigDecimal(quantity));
        balance.setAvailableQuantity(new BigDecimal(quantity));
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(new BigDecimal(value));
        return balance;
    }

    private StockBatch batch(UUID id, String number, String quantity) {
        StockBatch batch = new StockBatch();
        batch.setId(id);
        batch.setTenantId(TENANT);
        batch.setCompanyId(COMPANY);
        batch.setMaterialId(MATERIAL);
        batch.setWarehouseId(WAREHOUSE);
        batch.setBatchNumber(number);
        batch.setQuantity(new BigDecimal(quantity));
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
