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
import com.enterprise.mm.entity.TransferShipmentAllocation;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockCostMovementType;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InventoryCostingExactOpeningTest {
    private static final String TENANT = "exact-opening-tenant";
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID MATERIAL = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    private final InventoryCostLayerRepository repository = mock(InventoryCostLayerRepository.class);
    private final StockCostLedgerService ledger = mock(StockCostLedgerService.class);
    private final StockValuationService valuation = mock(StockValuationService.class);
    private final InventoryCostingService service = new InventoryCostingService(repository, ledger,
        mock(StockBalanceRepository.class), valuation, mock(MaterialRepository.class), mock(WarehouseRepository.class));
    private final List<InventoryCostLayer> layers = new ArrayList<>();
    private final List<StockCostLedgerService.RecordCommand> commands = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        when(repository.save(any(InventoryCostLayer.class))).thenAnswer(invocation -> {
            InventoryCostLayer layer = invocation.getArgument(0);
            if (layer.getId() == null) layer.setId(UUID.randomUUID());
            if (!layers.contains(layer)) layers.add(layer);
            return layer;
        });
        when(repository.existsByTenantIdAndCompanyIdAndMaterialIdAndWarehouseIdAndIsDeletedFalse(
            TENANT, COMPANY, MATERIAL, WAREHOUSE)).thenReturn(true);
        when(repository.findActiveLayersForUpdateAsc(TENANT, COMPANY, MATERIAL, WAREHOUSE, BATCH, BigDecimal.ZERO))
            .thenAnswer(invocation -> activeLayers());
        when(ledger.record(any())).thenAnswer(invocation -> {
            StockCostLedgerService.RecordCommand command = invocation.getArgument(0);
            commands.add(command);
            StockCostMovement result = new StockCostMovement();
            result.setId(UUID.randomUUID());
            result.setQuantity(command.quantity());
            result.setUnitCost(command.unitCost());
            result.setTotalCost(command.totalCost());
            return result;
        });
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void exactOpeningKeepsBookValueIndependentOfRoundedUnitCost() {
        StockMovement movement = opening("3.0000000", "1.00");
        InventoryCostingService.InboundLayerResult result = service.recordExactOpeningLayer(movement, new BigDecimal("1.00"));

        assertThat(result.quantityDelta()).isEqualByComparingTo("3.0000000");
        assertThat(result.valueDelta()).isEqualByComparingTo("1.00");
        assertThat(result.unitCost()).isEqualByComparingTo("0.333333");
        InventoryCostLayer layer = layers.getFirst();
        assertThat(layer.isAuthoritativeValue()).isTrue();
        assertThat(layer.getTotalCost()).isEqualByComparingTo("1.00");
        assertThat(layer.getReceivedQuantity()).isEqualByComparingTo("3.0000000");
        assertThat(layer.getSourceMovementId()).isEqualTo(movement.getId());
        assertThat(layer.getWarehouseId()).isEqualTo(WAREHOUSE);
        assertThat(layer.getStockBatchId()).isEqualTo(BATCH);
        assertThat(commands.getFirst().movementType()).isEqualTo(StockCostMovementType.OPENING_BALANCE);
        assertThat(commands.getFirst().totalCost()).isEqualByComparingTo("1.00");
        assertThat(result.costMovementId()).isNotNull();
    }

    @Test
    void exactOpeningKeepsSevenDecimalQuantityAndActiveZeroValue() {
        InventoryCostingService.InboundLayerResult result = service.recordExactOpeningLayer(
            opening("0.1234567", "0.00"), BigDecimal.ZERO);
        assertThat(result.quantityDelta()).isEqualByComparingTo("0.1234567");
        assertThat(result.valueDelta()).isZero();
        assertThat(layers.getFirst().getStatus()).isEqualTo("ACTIVE");
        assertThat(layers.getFirst().getRemainingQuantity()).isEqualByComparingTo("0.1234567");
    }

    @Test
    void exactOpeningRejectsUnmanagedProvenanceAndNonCentValues() {
        StockMovement unmanaged = opening("1", "0.00");
        unmanaged.setReferenceType("PURCHASE_RECEIPT");
        assertThatThrownBy(() -> service.recordExactOpeningLayer(unmanaged, BigDecimal.ZERO))
            .isInstanceOf(BusinessException.class).hasMessageContaining("managed opening");
        assertThatThrownBy(() -> service.recordExactOpeningLayer(opening("1", "0.001"), new BigDecimal("0.001")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("not rounded");
        assertThatThrownBy(() -> service.recordExactOpeningLayer(opening("1", "1.00"), new BigDecimal("2.00")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("must match");
        assertThatThrownBy(() -> service.recordExactOpeningLayer(opening("1", "-1.00"), new BigDecimal("-1.00")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("nonnegative");
        assertThat(layers).isEmpty();
        assertThat(commands).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ValuationMethod.class, names = {"FIFO", "LIFO", "SPECIFIC_BATCH", "WEIGHTED_AVERAGE", "MOVING_AVERAGE", "ACTUAL_COST"})
    void recurringUnitPriceFullyDepletesWithoutLosingBookValue(ValuationMethod method) {
        service.recordExactOpeningLayer(opening("3", "1.00"), new BigDecimal("1.00"));
        BigDecimal issuedValue = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            InventoryCostingService.CostResult result = issue("1", method);
            issuedValue = issuedValue.add(result.totalCost());
            assertThat(issuedValue.add(layers.getFirst().getTotalCost())).isEqualByComparingTo("1.00");
            assertThat(result.consumptions()).allSatisfy(c -> assertThat(c.costMovementId()).isNotNull());
        }
        assertThat(issuedValue).isEqualByComparingTo("1.00");
        assertThat(layers.getFirst().getRemainingQuantity()).isZero();
        assertThat(layers.getFirst().getTotalCost()).isZero();
        assertThat(layers.getFirst().getStatus()).isEqualTo("CONSUMED");
    }

    @Test
    void weightedPoolConservesDifferentSourceAccountValuesOnPartialAndFinalIssues() {
        layer("1", "1.00", true);
        layer("1", "100.00", true);
        InventoryCostingService.CostResult partial = issue("1", ValuationMethod.WEIGHTED_AVERAGE);
        assertThat(partial.totalCost()).isEqualByComparingTo("50.5000");
        assertThat(partial.consumptions()).hasSize(2);
        assertThat(partial.consumptions().getFirst().totalCost()).isEqualByComparingTo("0.5000");
        assertThat(partial.consumptions().getLast().totalCost()).isEqualByComparingTo("50.0000");
        assertThat(remainingQuantity()).isEqualByComparingTo("1");
        assertThat(remainingValue()).isEqualByComparingTo("50.5000");
        InventoryCostingService.CostResult last = issue("1", ValuationMethod.WEIGHTED_AVERAGE);
        assertThat(partial.totalCost().add(last.totalCost())).isEqualByComparingTo("101.00");
        assertThat(remainingQuantity()).isZero();
        assertThat(remainingValue()).isZero();
    }

    @Test
    void weightedTinyQuantityUsesSevenDecimalsAndPreservesPooledMoneyResidual() {
        layer("0.0000003", "0.01", true);
        layer("0.0000007", "0.02", true);
        InventoryCostingService.CostResult partial = issue("0.0000001", ValuationMethod.WEIGHTED_AVERAGE);
        assertThat(partial.consumptions().stream().map(InventoryCostingService.LayerConsumption::quantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0.0000001");
        assertThat(partial.totalCost()).isEqualByComparingTo("0.0030");
        assertThat(remainingQuantity()).isEqualByComparingTo("0.0000009");
        assertThat(remainingValue()).isEqualByComparingTo("0.0270");
        InventoryCostingService.CostResult last = issue("0.0000009", ValuationMethod.WEIGHTED_AVERAGE);
        assertThat(partial.totalCost().add(last.totalCost())).isEqualByComparingTo("0.03");
        assertThat(remainingQuantity()).isZero();
        assertThat(remainingValue()).isZero();
    }

    @Test
    void zeroValuedWeightedStockCanBeIssuedToFinalDepletion() {
        layer("3", "0.00", true);
        layer("2", "0.00", true);
        assertThat(issue("1", ValuationMethod.WEIGHTED_AVERAGE).totalCost()).isZero();
        assertThat(remainingQuantity()).isEqualByComparingTo("4");
        assertThat(issue("4", ValuationMethod.WEIGHTED_AVERAGE).totalCost()).isZero();
        assertThat(remainingQuantity()).isZero();
        assertThat(remainingValue()).isZero();
    }

    @Test
    void impossibleQuantumAllocationFailsBeforeChangingLayers() {
        layer("0.0000001", "1.00", true);
        layer("0.0000001", "0.00", true);
        assertThatThrownBy(() -> issue("0.0000001", ValuationMethod.WEIGHTED_AVERAGE))
            .isInstanceOf(BusinessException.class).hasMessageContaining("without revaluation");
        assertThat(remainingQuantity()).isEqualByComparingTo("0.0000002");
        assertThat(remainingValue()).isEqualByComparingTo("1.00");
        assertThat(commands).isEmpty();
    }

    @Test
    void analyticalValuationIsExplicitlyRejectedForAuthoritativeOpeningLayers() {
        layer("2", "10.00", true);
        assertThatThrownBy(() -> issue("1", ValuationMethod.MARKET_PRICE))
            .isInstanceOf(BusinessException.class).hasMessageContaining("book-value revaluation");
        assertThat(remainingValue()).isEqualByComparingTo("10.00");
        assertThat(commands).isEmpty();
    }

    @Test
    void existingUnflaggedAnalyticalWorkflowRemainsAvailable() {
        layer("2", "10.00", false);
        when(valuation.calculateMarketPriceCost(MATERIAL, List.of(WAREHOUSE), BigDecimal.ONE))
            .thenReturn(new BigDecimal("4.00"));
        assertThat(issue("1", ValuationMethod.MARKET_PRICE).totalCost()).isEqualByComparingTo("4.00");
        assertThat(remainingValue()).isEqualByComparingTo("6.00");
    }

    @Test
    void transferReceiptRetainsAuthoritativeParentBookValue() {
        InventoryCostLayer parent = layer("3", "1.00", true);
        when(repository.findByIdAndIsDeletedFalse(parent.getId())).thenReturn(Optional.of(parent));
        TransferShipmentAllocation origin = new TransferShipmentAllocation();
        origin.setConsumedCostLayerIds(List.of(parent.getId()));
        origin.setSourceWarehouseId(WAREHOUSE);
        origin.setCurrencyCode("UZS");
        origin.setExchangeRate(BigDecimal.ONE);
        StockMovement receipt = opening("1", "0.3333");
        receipt.setMovementDate(java.time.LocalDate.of(2026, 10, 8));
        service.recordTransferInboundLayer(receipt, origin, new BigDecimal("0.3333"));
        assertThat(layers.getLast().isAuthoritativeValue()).isTrue();
        assertThat(layers.getLast().getTotalCost()).isEqualByComparingTo("0.3333");
        assertThat(layers.getLast().getParentCostLayerId()).isEqualTo(parent.getId());
    }

    private List<InventoryCostLayer> activeLayers() {
        return layers.stream().filter(l -> l.getRemainingQuantity().signum() > 0).toList();
    }

    private StockMovement opening(String quantity, String value) {
        StockMovement movement = movement();
        movement.setMovementType(MovementType.RECEIPT);
        movement.setReferenceType("OPENING_STOCK_EXACT");
        movement.setReferenceId(RUN);
        movement.setQuantity(new BigDecimal(quantity));
        movement.setTotalCost(new BigDecimal(value));
        movement.setPostedAt(LocalDateTime.of(2026, 10, 8, 23, 59));
        return movement;
    }

    private StockMovement movement() {
        StockMovement movement = new StockMovement();
        movement.setId(UUID.randomUUID());
        movement.setTenantId(TENANT);
        movement.setCompanyId(COMPANY);
        movement.setMaterialId(MATERIAL);
        movement.setWarehouseId(WAREHOUSE);
        movement.setStockBatchId(BATCH);
        movement.setMovementType(MovementType.ISSUE);
        return movement;
    }

    @ParameterizedTest
    @EnumSource(value = ValuationMethod.class, names = {"WEIGHTED_AVERAGE", "MOVING_AVERAGE", "ACTUAL_COST"})
    void legacyDifferentlyPricedLayersConservePooledValue(ValuationMethod method) {
        layer("1", "1.00", false);
        layer("1", "100.00", false);
        var partial = issue("1", method);
        assertThat(partial.totalCost()).isEqualByComparingTo("50.50");
        assertThat(remainingValue()).isEqualByComparingTo("50.50");
        assertThat(partial.totalCost().add(remainingValue())).isEqualByComparingTo("101.00");
        var last = issue("1", method);
        assertThat(partial.totalCost().add(last.totalCost())).isEqualByComparingTo("101.00");
        assertThat(remainingQuantity()).isZero();
        assertThat(remainingValue()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = ValuationMethod.class, names = {"FIFO", "LIFO", "SPECIFIC_BATCH"})
    void legacyRecurringUnitPriceConsumesTheLastMoneyQuantum(ValuationMethod method) {
        layer("3", "1.00", false);
        BigDecimal issued = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            issued = issued.add(issue("1", method).totalCost());
            assertThat(issued.add(remainingValue())).isEqualByComparingTo("1.00");
        }
        assertThat(issued).isEqualByComparingTo("1.00");
        assertThat(remainingValue()).isZero();
        assertThat(remainingQuantity()).isZero();
    }

    private InventoryCostingService.CostResult issue(String quantity, ValuationMethod method) {
        return service.consumeOutboundLayers(movement(), WAREHOUSE, new BigDecimal(quantity), method);
    }

    private InventoryCostLayer layer(String quantity, String value, boolean authoritative) {
        InventoryCostLayer layer = new InventoryCostLayer();
        layer.setId(UUID.randomUUID());
        layer.setTenantId(TENANT);
        layer.setCompanyId(COMPANY);
        layer.setMaterialId(MATERIAL);
        layer.setWarehouseId(WAREHOUSE);
        layer.setStockBatchId(BATCH);
        layer.setRemainingQuantity(new BigDecimal(quantity));
        layer.setReceivedQuantity(new BigDecimal(quantity));
        layer.setTotalCost(new BigDecimal(value));
        layer.setUnitCost(new BigDecimal(value).divide(new BigDecimal(quantity), 6, RoundingMode.HALF_UP));
        layer.setReceivedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(layers.size()));
        layer.setAuthoritativeValue(authoritative);
        layer.setStatus("ACTIVE");
        layer.setCurrencyCode("UZS");
        layer.setExchangeRate(BigDecimal.ONE);
        layers.add(layer);
        return layer;
    }

    private BigDecimal remainingQuantity() {
        return layers.stream().map(InventoryCostLayer::getRemainingQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal remainingValue() {
        return layers.stream().map(InventoryCostLayer::getTotalCost).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
