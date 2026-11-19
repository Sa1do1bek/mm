package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.entity.StockCostMovement;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InventoryCostingExactReversalTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID LAYER_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ORIGINAL_COST_MOVEMENT = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID REVERSAL_COST_MOVEMENT = UUID.fromString("60000000-0000-0000-0000-000000000001");

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
        when(layerRepository.save(any(InventoryCostLayer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerService.record(any())).thenAnswer(invocation -> {
            StockCostLedgerService.RecordCommand command = invocation.getArgument(0);
            StockCostMovement persisted = new StockCostMovement();
            persisted.setId(REVERSAL_COST_MOVEMENT);
            persisted.setQuantity(command.quantity());
            persisted.setTotalCost(command.totalCost());
            persisted.setUnitCost(command.unitCost());
            return persisted;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void reversesConsumedLayerByRestoringExactPersistedQuantityAndValue() {
        InventoryCostLayer layer = layer("3", "12", "5");
        when(layerRepository.findByIdAndIsDeletedFalseForUpdate(LAYER_ID)).thenReturn(Optional.of(layer));

        List<InventoryCostingService.ExactReversalResult> results = service.reverseExactLineages(
            reversalMovement(),
            WAREHOUSE,
            List.of(new InventoryCostingService.ExactReversalLineage(
                LAYER_ID,
                null,
                ORIGINAL_COST_MOVEMENT,
                new BigDecimal("-2"),
                new BigDecimal("-8"),
                new BigDecimal("4.000000"),
                "UZS",
                BigDecimal.ONE
            )),
            "Operator correction"
        );

        assertThat(layer.getRemainingQuantity()).isEqualByComparingTo("5");
        assertThat(layer.getTotalCost()).isEqualByComparingTo("20");
        assertThat(layer.getUnitCost()).isEqualByComparingTo("4.000000");
        assertThat(layer.getStatus()).isEqualTo("ACTIVE");
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.costLayerId()).isEqualTo(LAYER_ID);
            assertThat(result.costMovementId()).isEqualTo(REVERSAL_COST_MOVEMENT);
            assertThat(result.quantityDelta()).isEqualByComparingTo("2");
            assertThat(result.valueDelta()).isEqualByComparingTo("8");
        });

        ArgumentCaptor<StockCostLedgerService.RecordCommand> commandCaptor =
            ArgumentCaptor.forClass(StockCostLedgerService.RecordCommand.class);
        verify(ledgerService).record(commandCaptor.capture());
        StockCostLedgerService.RecordCommand command = commandCaptor.getValue();
        assertThat(command.reversal()).isTrue();
        assertThat(command.reversalOfId()).isEqualTo(ORIGINAL_COST_MOVEMENT);
        assertThat(command.stockMovementId()).isEqualTo(reversalMovement().getId());
        assertThat(command.quantity()).isEqualByComparingTo("2");
        assertThat(command.totalCost()).isEqualByComparingTo("8");
    }

    @Test
    void reversesCreatedLayerOnlyWhenItsExactQuantityAndValueStillRemain() {
        InventoryCostLayer layer = layer("2", "10", "2");
        when(layerRepository.findByIdAndIsDeletedFalseForUpdate(LAYER_ID)).thenReturn(Optional.of(layer));

        service.reverseExactLineages(
            reversalMovement(),
            WAREHOUSE,
            List.of(new InventoryCostingService.ExactReversalLineage(
                LAYER_ID, null, ORIGINAL_COST_MOVEMENT,
                new BigDecimal("2"), new BigDecimal("10"), new BigDecimal("5"),
                "UZS", BigDecimal.ONE
            )),
            "Receipt cancellation"
        );

        assertThat(layer.getRemainingQuantity()).isZero();
        assertThat(layer.getTotalCost()).isZero();
        assertThat(layer.getStatus()).isEqualTo("CONSUMED");
    }

    @Test
    void refusesToRecomputeOrClampWhenCreatedLayerWasAlreadyConsumed() {
        InventoryCostLayer layer = layer("1", "5", "2");
        when(layerRepository.findByIdAndIsDeletedFalseForUpdate(LAYER_ID)).thenReturn(Optional.of(layer));

        assertThatThrownBy(() -> service.reverseExactLineages(
            reversalMovement(),
            WAREHOUSE,
            List.of(new InventoryCostingService.ExactReversalLineage(
                LAYER_ID, null, ORIGINAL_COST_MOVEMENT,
                new BigDecimal("2"), new BigDecimal("10"), new BigDecimal("5"),
                "UZS", BigDecimal.ONE
            )),
            "Receipt cancellation"
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Exact cost layer reversal", "negative");

        assertThat(layer.getRemainingQuantity()).isEqualByComparingTo("1");
        verify(ledgerService, never()).record(any());
    }

    private InventoryCostLayer layer(String remaining, String totalCost, String received) {
        InventoryCostLayer layer = new InventoryCostLayer();
        layer.setId(LAYER_ID);
        layer.setTenantId(TENANT);
        layer.setCompanyId(COMPANY);
        layer.setMaterialId(MATERIAL);
        layer.setWarehouseId(WAREHOUSE);
        layer.setReceivedQuantity(new BigDecimal(received));
        layer.setRemainingQuantity(new BigDecimal(remaining));
        layer.setTotalCost(new BigDecimal(totalCost));
        layer.setUnitCost(new BigDecimal("4"));
        layer.setCurrencyCode("UZS");
        layer.setExchangeRate(BigDecimal.ONE);
        layer.setReceivedAt(LocalDateTime.now());
        layer.setStatus("ACTIVE");
        return layer;
    }

    private StockMovement reversalMovement() {
        StockMovement movement = new StockMovement();
        movement.setId(UUID.fromString("70000000-0000-0000-0000-000000000001"));
        movement.setTenantId(TENANT);
        movement.setCompanyId(COMPANY);
        movement.setMaterialId(MATERIAL);
        movement.setWarehouseId(WAREHOUSE);
        movement.setMovementType(MovementType.REVERSAL);
        movement.setReferenceType("REVERSAL");
        movement.setReferenceId(UUID.fromString("80000000-0000-0000-0000-000000000001"));
        return movement;
    }
}
