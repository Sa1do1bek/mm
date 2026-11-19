package com.enterprise.mm.warehouse.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseReconciliationServiceTest {
    private final WarehouseReconciliationQuery query = mock(WarehouseReconciliationQuery.class);
    private final WarehouseReconciliationService service = new WarehouseReconciliationService(query);

    @Test
    void reconcileReturnsBalancedWhenSourceAndProvenMatchExactly() {
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        var position = new ReconciliationPosition(
            materialId, "MAT-01", "Nitrogen Fertilizer", "KG",
            warehouseId, "WH-MAIN", "Central Depot",
            "1010", "Materials",
            new BigDecimal("500.0000000"), new BigDecimal("125000.0000"),
            new BigDecimal("500.0000000"), new BigDecimal("125000.0000"),
            new BigDecimal("500.0000000"), new BigDecimal("125000.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            ReconciliationStatus.BALANCED
        );

        var summary = new ReconciliationSummary(
            1, 1, 0, 0, 0, 0,
            new BigDecimal("500.0000000"), new BigDecimal("125000.0000"),
            new BigDecimal("500.0000000"), new BigDecimal("125000.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO
        );

        var expectedResult = new WarehouseReconciliationResult(summary, List.of(position), 0, 50, 1);
        when(query.reconcile(any())).thenReturn(expectedResult);

        var filter = new ReconciliationFilter("1010", warehouseId, null, null, 0, 50);
        var result = service.reconcile(filter);

        assertThat(result.summary().totalPositions()).isEqualTo(1);
        assertThat(result.summary().balancedPositions()).isEqualTo(1);
        assertThat(result.summary().discrepancyPositions()).isZero();
        assertThat(result.items()).hasSize(1);

        ReconciliationPosition item = result.items().get(0);
        assertThat(item.status()).isEqualTo(ReconciliationStatus.BALANCED);
        assertThat(item.quantityDelta()).isZero();
        assertThat(item.amountDelta()).isZero();
    }

    @Test
    void reconcileIdentifiesDiscrepancyWhenQuantitiesOrValuesDiffer() {
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        var position = new ReconciliationPosition(
            materialId, "MAT-02", "Industrial Salt", "TN",
            warehouseId, "WH-MAIN", "Central Depot",
            "1010", "Materials",
            new BigDecimal("100.0000000"), new BigDecimal("20000.0000"),
            new BigDecimal("80.0000000"), new BigDecimal("16000.0000"),
            new BigDecimal("80.0000000"), new BigDecimal("16000.0000"),
            new BigDecimal("-20.0000000"), new BigDecimal("-4000.0000"),
            ReconciliationStatus.DISCREPANCY
        );

        var summary = new ReconciliationSummary(
            1, 0, 1, 0, 0, 0,
            new BigDecimal("100.0000000"), new BigDecimal("20000.0000"),
            new BigDecimal("80.0000000"), new BigDecimal("16000.0000"),
            new BigDecimal("-20.0000000"), new BigDecimal("-4000.0000")
        );

        when(query.reconcile(any())).thenReturn(new WarehouseReconciliationResult(summary, List.of(position), 0, 50, 1));

        var filter = new ReconciliationFilter("1010", warehouseId, null, ReconciliationStatus.DISCREPANCY, 0, 50);
        var result = service.reconcile(filter);

        assertThat(result.summary().discrepancyPositions()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo(ReconciliationStatus.DISCREPANCY);
        assertThat(result.items().get(0).quantityDelta()).isEqualByComparingTo("-20.0000000");
        assertThat(result.items().get(0).amountDelta()).isEqualByComparingTo("-4000.0000");
    }

    @Test
    void reconcileIdentifiesSourceOnlyAndNativeOnlyPositions() {
        var srcOnly = new ReconciliationPosition(
            UUID.randomUUID(), "MAT-03", "Catalyst A", "KG",
            UUID.randomUUID(), "WH-01", "Store 1",
            "1020", "Spare Parts",
            new BigDecimal("50.0000000"), new BigDecimal("10000.0000"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("-50.0000000"), new BigDecimal("-10000.0000"),
            ReconciliationStatus.SOURCE_ONLY
        );

        var nativeOnly = new ReconciliationPosition(
            UUID.randomUUID(), "MAT-04", "Pipe Fitting", "PCS",
            UUID.randomUUID(), "WH-01", "Store 1",
            "1020", "Spare Parts",
            BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("25.0000000"), new BigDecimal("5000.0000"),
            new BigDecimal("25.0000000"), new BigDecimal("5000.0000"),
            new BigDecimal("25.0000000"), new BigDecimal("5000.0000"),
            ReconciliationStatus.NATIVE_ONLY
        );

        var summary = new ReconciliationSummary(
            2, 0, 0, 1, 1, 0,
            new BigDecimal("50.0000000"), new BigDecimal("10000.0000"),
            new BigDecimal("25.0000000"), new BigDecimal("5000.0000"),
            new BigDecimal("-25.0000000"), new BigDecimal("-5000.0000")
        );

        when(query.reconcile(any())).thenReturn(new WarehouseReconciliationResult(summary, List.of(srcOnly, nativeOnly), 0, 50, 2));

        var filter = new ReconciliationFilter("1020", null, null, null, 0, 50);
        var result = service.reconcile(filter);

        assertThat(result.summary().sourceOnlyPositions()).isEqualTo(1);
        assertThat(result.summary().nativeOnlyPositions()).isEqualTo(1);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).status()).isEqualTo(ReconciliationStatus.SOURCE_ONLY);
        assertThat(result.items().get(1).status()).isEqualTo(ReconciliationStatus.NATIVE_ONLY);
    }
}
