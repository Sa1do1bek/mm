package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.entity.StockCostMovement;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.InventoryCostLayerType;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockCostMovementType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InventoryCostingInboundEvidenceTest {
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID BATCH = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID LAYER = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID COST_MOVEMENT = UUID.fromString("60000000-0000-0000-0000-000000000001");

    private final InventoryCostLayerRepository costLayerRepository = mock(InventoryCostLayerRepository.class);
    private final StockCostLedgerService stockCostLedgerService = mock(StockCostLedgerService.class);
    private final InventoryCostingService service = new InventoryCostingService(
        costLayerRepository,
        stockCostLedgerService,
        mock(StockBalanceRepository.class),
        mock(StockValuationService.class),
        mock(MaterialRepository.class),
        mock(WarehouseRepository.class)
    );

    @BeforeEach
    void assignPersistenceIds() {
        when(costLayerRepository.save(any(InventoryCostLayer.class))).thenAnswer(invocation -> {
            InventoryCostLayer layer = invocation.getArgument(0);
            layer.setId(LAYER);
            return layer;
        });
        when(stockCostLedgerService.record(any())).thenAnswer(invocation -> {
            StockCostLedgerService.RecordCommand command = invocation.getArgument(0);
            StockCostMovement movement = new StockCostMovement();
            movement.setId(COST_MOVEMENT);
            movement.setQuantity(command.quantity());
            movement.setUnitCost(command.unitCost());
            movement.setTotalCost(command.totalCost());
            movement.setCostLayerId(command.costLayerId());
            return movement;
        });
    }

    @ParameterizedTest
    @MethodSource("inboundCases")
    void returnsExactCreatedLayerAndLedgerEvidence(
        MovementType movementType,
        InventoryCostLayerType layerType,
        StockCostMovementType expectedLedgerType
    ) {
        StockMovement movement = movement(movementType);

        InventoryCostingService.InboundLayerResult result = service.recordInboundLayer(
            movement,
            WAREHOUSE,
            BATCH,
            new BigDecimal("2.5000"),
            new BigDecimal("3.200000"),
            layerType
        );

        assertThat(result.costLayerId()).isEqualTo(LAYER);
        assertThat(result.parentCostLayerId()).isNull();
        assertThat(result.costMovementId()).isEqualTo(COST_MOVEMENT);
        assertThat(result.quantityDelta()).isEqualByComparingTo("2.5000");
        assertThat(result.valueDelta()).isEqualByComparingTo("8.0000");
        assertThat(result.unitCost()).isEqualByComparingTo("3.200000");
        assertThat(result.currencyCode()).isEqualTo("UZS");
        assertThat(result.exchangeRate()).isEqualByComparingTo(BigDecimal.ONE);

        ArgumentCaptor<StockCostLedgerService.RecordCommand> command =
            ArgumentCaptor.forClass(StockCostLedgerService.RecordCommand.class);
        org.mockito.Mockito.verify(stockCostLedgerService).record(command.capture());
        assertThat(command.getValue().movementType()).isEqualTo(expectedLedgerType);
        assertThat(command.getValue().quantity()).isEqualByComparingTo(result.quantityDelta());
        assertThat(command.getValue().totalCost()).isEqualByComparingTo(result.valueDelta());
    }

    @Test
    void rejectsMissingLayerOrCostMovementIdentity() {
        when(costLayerRepository.save(any(InventoryCostLayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThatThrownBy(() -> service.recordInboundLayer(
            movement(MovementType.RECEIPT), WAREHOUSE, BATCH, BigDecimal.ONE, BigDecimal.TEN,
            InventoryCostLayerType.RECEIPT))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cost layer id");

        when(costLayerRepository.save(any(InventoryCostLayer.class))).thenAnswer(invocation -> {
            InventoryCostLayer layer = invocation.getArgument(0);
            layer.setId(LAYER);
            return layer;
        });
        doReturn(new StockCostMovement()).when(stockCostLedgerService).record(any());
        assertThatThrownBy(() -> service.recordInboundLayer(
            movement(MovementType.ADJUSTMENT), WAREHOUSE, BATCH, BigDecimal.ONE, BigDecimal.TEN,
            InventoryCostLayerType.ADJUSTMENT_IN))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cost movement id");
    }

    private StockMovement movement(MovementType type) {
        StockMovement movement = new StockMovement();
        movement.setId(UUID.fromString("70000000-0000-0000-0000-000000000001"));
        movement.setCompanyId(COMPANY);
        movement.setMaterialId(MATERIAL);
        movement.setMovementType(type);
        movement.setReferenceType("TEST");
        movement.setReferenceId(UUID.fromString("80000000-0000-0000-0000-000000000001"));
        return movement;
    }

    @Test
    void productionLayerStoresExactAllocationAsAuthoritativeBookValue() {
        StockMovement movement = movement(MovementType.RECEIPT);
        movement.setReferenceType("PRODUCTION_OPERATION");
        movement.setQuantity(new BigDecimal("3")); movement.setUnitCost(new BigDecimal("0.3333"));
        movement.setTotalCost(new BigDecimal("1.0000"));
        var result = service.recordProductionLayer(movement, WAREHOUSE, BATCH);
        assertThat(result.valueDelta()).isEqualByComparingTo("1");
        ArgumentCaptor<InventoryCostLayer> layer = ArgumentCaptor.forClass(InventoryCostLayer.class);
        org.mockito.Mockito.verify(costLayerRepository).save(layer.capture());
        assertThat(layer.getValue().isAuthoritativeValue()).isTrue();
        assertThat(layer.getValue().getTotalCost()).isEqualByComparingTo("1");
    }

    private static Stream<Arguments> inboundCases() {
        return Stream.of(
            Arguments.of(MovementType.RECEIPT, InventoryCostLayerType.RECEIPT, StockCostMovementType.RECEIPT),
            Arguments.of(MovementType.RETURN, InventoryCostLayerType.RECEIPT, StockCostMovementType.RECEIPT),
            Arguments.of(MovementType.ADJUSTMENT, InventoryCostLayerType.ADJUSTMENT_IN, StockCostMovementType.ADJUSTMENT)
        );
    }
}
