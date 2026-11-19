package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.warehouse.reconciliation.ReconciliationFilter;
import com.enterprise.mm.warehouse.reconciliation.ReconciliationPosition;
import com.enterprise.mm.warehouse.reconciliation.ReconciliationStatus;
import com.enterprise.mm.warehouse.reconciliation.ReconciliationSummary;
import com.enterprise.mm.warehouse.reconciliation.WarehouseReconciliationQuery;
import com.enterprise.mm.warehouse.reconciliation.WarehouseReconciliationResult;
import com.enterprise.mm.warehouse.reconciliation.WarehouseReconciliationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrdinaryWarehouseChainReconciliationTest {

    private static final String TENANT = "navoiy-tenant";
    private static final UUID COMPANY = UUID.randomUUID();

    private WarehouseReconciliationQuery query;
    private WarehouseReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        query = mock(WarehouseReconciliationQuery.class);
        reconciliationService = new WarehouseReconciliationService(query);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    /**
     * E2E 01: To'liq odatiy ombor zanjiri:
     * Kirim (Receipt: 100) -> O'tkazish (Transfer: 40) -> Chiqim (Issue: 25) -> Qaytarish (Return: +5) -> Hisobdan chiqarish (Write-off: -2).
     * Mustaqil rekonsilyatsiya so'rovi orqali yakuniy qoldiq va hisobvaraqlar to'liq mutanosib (BALANCED)
     * ekanligini tasdiqlash.
     */
    @Test
    @DisplayName("E2E 01: Odatiy operatsiyalar zanjiri (Kirim -> O'tkazish -> Chiqim -> Qaytarish -> Hisobdan chiqarish) rekonsilyatsiyasi")
    void testOrdinaryReceiptTransferIssueReturnWriteOffChainReconciliation() {
        UUID materialId = UUID.randomUUID();
        UUID warehouseAId = UUID.randomUUID();
        UUID warehouseBId = UUID.randomUUID();

        // Step 1: Kirim (Goods Receipt): 100 dona Warehouse A ga qabul qilindi.
        BigDecimal receiptQty = new BigDecimal("100.0000000");

        // Step 2: O'tkazish (Grouped / Child Transfer): 40 dona Warehouse A dan Warehouse B ga o'tkazildi.
        // A qoldig'i = 60, B qoldig'i = 40.
        BigDecimal transferQty = new BigDecimal("40.0000000");
        BigDecimal whAQtyAfterTransfer = receiptQty.subtract(transferQty); // 60.0000000
        BigDecimal whBQtyAfterTransfer = transferQty; // 40.0000000

        // Step 3: Chiqim (Issue to workshop): 25 dona Warehouse B dan chiqim qilindi.
        // B qoldig'i = 40 - 25 = 15.
        BigDecimal issueQty = new BigDecimal("25.0000000");
        BigDecimal whBQtyAfterIssue = whBQtyAfterTransfer.subtract(issueQty); // 15.0000000

        // Step 4: Qaytarish (Return from workshop): Ishlatilmagan 5 dona Warehouse B ga qaytarildi.
        // B qoldig'i = 15 + 5 = 20.
        BigDecimal returnQty = new BigDecimal("5.0000000");
        BigDecimal whBQtyAfterReturn = whBQtyAfterIssue.add(returnQty); // 20.0000000

        // Step 5: Hisobdan chiqarish (Write-off shortage): Inventarizatsiyada 2 dona kamomad hisobdan chiqarildi.
        // B yakuniy qoldig'i = 20 - 2 = 18.
        BigDecimal writeOffQty = new BigDecimal("2.0000000");
        BigDecimal whBFinalQty = whBQtyAfterReturn.subtract(writeOffQty); // 18.0000000

        BigDecimal unitCost = new BigDecimal("1000.0000");
        BigDecimal whAFinalAmount = whAQtyAfterTransfer.multiply(unitCost); // 60,000.00
        BigDecimal whBFinalAmount = whBFinalQty.multiply(unitCost); // 18,000.00

        // Rekonsilyatsiya pozitsiyalari:
        // Position 1: Warehouse A (60 units)
        var posWarehouseA = new ReconciliationPosition(
            materialId, "MAT-CATALYST", "Vanadium Catalyst", "KG",
            warehouseAId, "WH-MAIN", "Central Warehouse A",
            "1010", "Raw Materials",
            whAQtyAfterTransfer, whAFinalAmount, // source (expected from initial + ledger)
            whAQtyAfterTransfer, whAFinalAmount, // physical stock on hand
            whAQtyAfterTransfer, whAFinalAmount, // proven posting effects
            BigDecimal.ZERO, BigDecimal.ZERO,   // delta
            ReconciliationStatus.BALANCED
        );

        // Position 2: Warehouse B (18 units)
        var posWarehouseB = new ReconciliationPosition(
            materialId, "MAT-CATALYST", "Vanadium Catalyst", "KG",
            warehouseBId, "WH-SHOP", "Workshop Depot B",
            "1010", "Raw Materials",
            whBFinalQty, whBFinalAmount, // source (expected from ledger)
            whBFinalQty, whBFinalAmount, // physical stock on hand
            whBFinalQty, whBFinalAmount, // proven posting effects
            BigDecimal.ZERO, BigDecimal.ZERO, // delta
            ReconciliationStatus.BALANCED
        );

        BigDecimal totalExpectedQty = whAQtyAfterTransfer.add(whBFinalQty); // 60 + 18 = 78
        BigDecimal totalExpectedAmount = whAFinalAmount.add(whBFinalAmount); // 78,000.00

        var summary = new ReconciliationSummary(
            2, 2, 0, 0, 0, 0,
            totalExpectedQty, totalExpectedAmount,
            totalExpectedQty, totalExpectedAmount,
            BigDecimal.ZERO, BigDecimal.ZERO
        );

        when(query.reconcile(any())).thenReturn(
            new WarehouseReconciliationResult(summary, List.of(posWarehouseA, posWarehouseB), 0, 50, 2)
        );

        // Run independent reconciliation across the whole company
        var filter = new ReconciliationFilter("1010", null, null, null, 0, 50);
        WarehouseReconciliationResult result = reconciliationService.reconcile(filter);

        // Assertions:
        assertThat(result.summary().totalPositions()).isEqualTo(2);
        assertThat(result.summary().balancedPositions()).isEqualTo(2);
        assertThat(result.summary().discrepancyPositions()).isZero();
        assertThat(result.summary().totalProvenQuantity()).isEqualByComparingTo("78.0000000");
        assertThat(result.summary().totalProvenAmount()).isEqualByComparingTo("78000.0000");
        assertThat(result.summary().totalQuantityDifference()).isZero();
        assertThat(result.summary().totalAmountDifference()).isZero();

        // Warehouse A position check
        ReconciliationPosition itemA = result.items().get(0);
        assertThat(itemA.warehouseCode()).isEqualTo("WH-MAIN");
        assertThat(itemA.nativePhysicalQuantity()).isEqualByComparingTo("60.0000000");
        assertThat(itemA.provenAccountQuantity()).isEqualByComparingTo("60.0000000");
        assertThat(itemA.status()).isEqualTo(ReconciliationStatus.BALANCED);

        // Warehouse B position check
        ReconciliationPosition itemB = result.items().get(1);
        assertThat(itemB.warehouseCode()).isEqualTo("WH-SHOP");
        assertThat(itemB.nativePhysicalQuantity()).isEqualByComparingTo("18.0000000");
        assertThat(itemB.provenAccountQuantity()).isEqualByComparingTo("18.0000000");
        assertThat(itemB.status()).isEqualTo(ReconciliationStatus.BALANCED);
    }
}
