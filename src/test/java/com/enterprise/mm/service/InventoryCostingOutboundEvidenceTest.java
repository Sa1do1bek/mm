package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.entity.StockCostMovement;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryCostingOutboundEvidenceTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID BATCH_1 = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID BATCH_2 = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID LAYER_1 = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID LAYER_2 = UUID.fromString("50000000-0000-0000-0000-000000000002");
    private static final UUID PARENT_1 = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID COST_MOVEMENT_1 = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID COST_MOVEMENT_2 = UUID.fromString("70000000-0000-0000-0000-000000000002");

    private final InventoryCostLayerRepository layerRepository = mock(InventoryCostLayerRepository.class);
    private final StockCostLedgerService ledgerService = mock(StockCostLedgerService.class);
    private final InventoryCostingService service = new InventoryCostingService(
        layerRepository,
        ledgerService,
        mock(StockBalanceRepository.class),
        mock(StockValuationService.class),
        mock(MaterialRepository.class),
        mock(WarehouseRepository.class)
    );

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        when(layerRepository.existsByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndIsDeletedFalse(
            TENANT, COMPANY, MATERIAL, WAREHOUSE)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void fifoReturnsPersistedCostMovementAndCompleteLayerLineageForEveryConsumedBatch() {
        InventoryCostLayer first = layer(LAYER_1, PARENT_1, BATCH_1, "2", "4.500000", "EUR", "1.250000");
        InventoryCostLayer second = layer(LAYER_2, null, BATCH_2, "3", "6.000000", "UZS", "1.000000");
        when(layerRepository.findActiveLayersForUpdateAsc(
            TENANT, COMPANY, MATERIAL, WAREHOUSE, null, BigDecimal.ZERO)).thenReturn(List.of(first, second));

        AtomicInteger recorded = new AtomicInteger();
        when(ledgerService.record(any())).thenAnswer(invocation -> {
            StockCostLedgerService.RecordCommand command = invocation.getArgument(0);
            StockCostMovement persisted = new StockCostMovement();
            persisted.setId(recorded.getAndIncrement() == 0 ? COST_MOVEMENT_1 : COST_MOVEMENT_2);
            persisted.setQuantity(command.quantity());
            persisted.setUnitCost(command.unitCost());
            persisted.setTotalCost(command.totalCost());
            return persisted;
        });

        InventoryCostingService.CostResult result = service.consumeOutboundLayers(
            movement(), WAREHOUSE, new BigDecimal("4"), ValuationMethod.FIFO);

        assertThat(result.totalCost()).isEqualByComparingTo("21.0000");
        assertThat(result.consumptions()).hasSize(2);
        assertThat(result.consumptions().get(0)).satisfies(consumption -> {
            assertThat(consumption.costLayerId()).isEqualTo(LAYER_1);
            assertThat(consumption.parentCostLayerId()).isEqualTo(PARENT_1);
            assertThat(consumption.stockBatchId()).isEqualTo(BATCH_1);
            assertThat(consumption.costMovementId()).isEqualTo(COST_MOVEMENT_1);
            assertThat(consumption.quantity()).isEqualByComparingTo("2");
            assertThat(consumption.totalCost()).isEqualByComparingTo("9");
            assertThat(consumption.currencyCode()).isEqualTo("EUR");
            assertThat(consumption.exchangeRate()).isEqualByComparingTo("1.250000");
        });
        assertThat(result.consumptions().get(1)).satisfies(consumption -> {
            assertThat(consumption.costLayerId()).isEqualTo(LAYER_2);
            assertThat(consumption.stockBatchId()).isEqualTo(BATCH_2);
            assertThat(consumption.costMovementId()).isEqualTo(COST_MOVEMENT_2);
            assertThat(consumption.quantity()).isEqualByComparingTo("2");
            assertThat(consumption.totalCost()).isEqualByComparingTo("12");
        });
    }

    @Test
    void unbatchedQuarantineUsesOnlyUnbatchedCostLayers() {
        var unbatched = layer(LAYER_1, null, null, "2", "10", "UZS", "1");
        var anotherBatch = layer(LAYER_2, null, BATCH_2, "5", "30", "UZS", "1");
        when(layerRepository.findActiveLayersForUpdateAsc(TENANT, COMPANY, MATERIAL, WAREHOUSE, null, BigDecimal.ZERO))
            .thenReturn(List.of(anotherBatch, unbatched));
        when(ledgerService.record(any())).thenAnswer(inv -> { var cost = new StockCostMovement(); cost.setId(UUID.randomUUID()); return cost; });
        var result = service.consumeOutboundLayersForStockBatch(movement(), WAREHOUSE, BigDecimal.ONE, ValuationMethod.FIFO);
        assertThat(result.consumptions()).singleElement().satisfies(consumption -> {
            assertThat(consumption.costLayerId()).isEqualTo(LAYER_1);
            assertThat(consumption.stockBatchId()).isNull();
        });
        assertThat(anotherBatch.getRemainingQuantity()).isEqualByComparingTo("5");
    }

    @Test
    void rejectsAnOutboundCostLineWithoutPersistedLedgerIdentity() {
        InventoryCostLayer layer = layer(LAYER_1, null, BATCH_1, "2", "4", "UZS", "1");
        when(layerRepository.findActiveLayersForUpdateAsc(
            TENANT, COMPANY, MATERIAL, WAREHOUSE, null, BigDecimal.ZERO)).thenReturn(List.of(layer));
        when(ledgerService.record(any())).thenReturn(new StockCostMovement());

        assertThatThrownBy(() -> service.consumeOutboundLayers(
            movement(), WAREHOUSE, BigDecimal.ONE, ValuationMethod.FIFO))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Outbound cost movement id");
    }

    private StockMovement movement() {
        StockMovement movement = new StockMovement();
        movement.setId(UUID.fromString("80000000-0000-0000-0000-000000000001"));
        movement.setTenantId(TENANT);
        movement.setCompanyId(COMPANY);
        movement.setMaterialId(MATERIAL);
        movement.setWarehouseId(WAREHOUSE);
        movement.setMovementType(MovementType.ISSUE);
        return movement;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ValuationMethod.class, names = {"FIFO", "LIFO"})
    void oppositeCostMethodsUseSameLockQueryButConsumeDifferentLayers(ValuationMethod method) {
        var old = layer(LAYER_1, null, BATCH_1, "2", "10", "UZS", "1");
        var newer = layer(LAYER_2, null, BATCH_2, "2", "30", "UZS", "1");
        old.setReceivedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        newer.setReceivedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(layerRepository.findActiveLayersForUpdateAsc(TENANT, COMPANY, MATERIAL, WAREHOUSE, null, BigDecimal.ZERO))
            .thenReturn(List.of(old, newer));
        when(ledgerService.record(any())).thenAnswer(inv -> {var cost = new StockCostMovement();cost.setId(UUID.randomUUID());return cost;});
        var result = service.consumeOutboundLayers(movement(), WAREHOUSE, new BigDecimal("3"), method);
        assertThat(result.consumptions().getFirst().costLayerId()).isEqualTo(method == ValuationMethod.FIFO ? LAYER_1 : LAYER_2);
        assertThat(result.totalCost()).isEqualByComparingTo(method == ValuationMethod.FIFO ? "50" : "70");
        assertThat(result.totalCost().add(old.getTotalCost()).add(newer.getTotalCost())).isEqualByComparingTo("80");
        org.mockito.Mockito.verify(layerRepository).findActiveLayersForUpdateAsc(TENANT, COMPANY, MATERIAL, WAREHOUSE, null, BigDecimal.ZERO);
    }

    private InventoryCostLayer layer(
        UUID id,
        UUID parentId,
        UUID batchId,
        String quantity,
        String unitCost,
        String currency,
        String exchangeRate
    ) {
        InventoryCostLayer layer = new InventoryCostLayer();
        layer.setId(id);
        layer.setTenantId(TENANT);
        layer.setCompanyId(COMPANY);
        layer.setMaterialId(MATERIAL);
        layer.setWarehouseId(WAREHOUSE);
        layer.setParentCostLayerId(parentId);
        layer.setStockBatchId(batchId);
        layer.setRemainingQuantity(new BigDecimal(quantity));
        layer.setReceivedQuantity(new BigDecimal(quantity));
        layer.setUnitCost(new BigDecimal(unitCost));
        layer.setTotalCost(new BigDecimal(quantity).multiply(new BigDecimal(unitCost)));
        layer.setCurrencyCode(currency);
        layer.setExchangeRate(new BigDecimal(exchangeRate));
        layer.setReceivedAt(LocalDateTime.now());
        layer.setStatus("ACTIVE");
        return layer;
    }
}
