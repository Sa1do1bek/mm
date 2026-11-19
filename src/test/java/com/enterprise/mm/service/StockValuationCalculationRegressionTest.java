package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockValuationCalculationRegressionTest {
    @Mock StockBatchRepository batches;
    @Mock StockBalanceRepository balances;
    @Mock StockValuationRepository valuations;
    @Mock StockMovementRepository movements;
    @Mock MaterialRepository materials;
    @Mock MaterialMarketPriceService prices;
    @Mock AuditService audit;
    @Mock StockValuationActionPolicy policy;
    @Mock MaterialCatalogGuard guard;
    @InjectMocks StockValuationService service;
    final UUID material = UUID.randomUUID();
    final UUID first = UUID.randomUUID();
    final UUID second = UUID.randomUUID();
    final UUID company = UUID.randomUUID();

    @BeforeEach void context() {
        TenantContext.setTenant("valuation-test");
        CompanyContext.setCompany(company);
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @ParameterizedTest @EnumSource(ValuationMethod.class)
    void rejectsNegativeAndZeroQuantities(ValuationMethod method) {
        var selection = new StockValuationCostSelectionService(service, mock(InventoryValuationPolicyService.class),
            balances, materials, mock(WarehouseRepository.class));
        for (BigDecimal quantity : List.of(BigDecimal.ZERO, BigDecimal.ONE.negate())) {
            assertThatThrownBy(() -> selection.calculateCost(method, material, List.of(first), null, quantity))
                .isInstanceOf(BusinessException.class).hasMessageContaining("positive");
        }
    }

    @Test void specificBatchCanBelongToSecondWarehouseOrGlobalScope() {
        StockBatch batch = new StockBatch();
        batch.setId(UUID.randomUUID()); batch.setTenantId(TenantContext.currentTenant());
        batch.setCompanyId(company); batch.setMaterialId(material); batch.setWarehouseId(second);
        batch.setQuantity(BigDecimal.TEN); batch.setUnitCost(new BigDecimal("7"));
        when(batches.findByIdAndIsDeletedFalse(batch.getId())).thenReturn(Optional.of(batch));
        assertThat(service.calculateSpecificBatchCost(material, List.of(first, second), batch.getId(), BigDecimal.TEN))
            .isEqualByComparingTo("70");
        assertThat(service.calculateSpecificBatchCost(material, List.of(), batch.getId(), BigDecimal.TEN))
            .isEqualByComparingTo("70");
    }

    @Test void specificBatchFullGlobalStockUsesAllBalancesWithoutNullFailure() {
        when(balances.findBalancesForValuation(anyString(), eq(material), eq(true), anyList()))
            .thenReturn(List.of(balance(first, "10", "50"), balance(second, "10", "80")));
        assertThat(service.calculateSpecificBatchCost(material, List.of(), null, new BigDecimal("20")))
            .isEqualByComparingTo("130");
    }

    @Test void marketAndNetRealizablePricesUseEachWarehouseAndNotListOrder() {
        when(balances.findBalancesForValuation(anyString(), eq(material), anyBoolean(), anyList()))
            .thenReturn(List.of(balance(first, "10", "50"), balance(second, "20", "160")));
        when(prices.approvedMarketUnitPrice(eq(material), eq(first), any())).thenReturn(new BigDecimal("5"));
        when(prices.approvedMarketUnitPrice(eq(material), eq(second), any())).thenReturn(new BigDecimal("8"));
        when(prices.approvedNetRealizableUnitPrice(eq(material), eq(first), any())).thenReturn(new BigDecimal("4"));
        when(prices.approvedNetRealizableUnitPrice(eq(material), eq(second), any())).thenReturn(new BigDecimal("7"));
        assertThat(service.calculateMarketPriceCost(material, List.of(first, second), new BigDecimal("30"))).isEqualByComparingTo("210");
        assertThat(service.calculateMarketPriceCost(material, List.of(second, first), new BigDecimal("30"))).isEqualByComparingTo("210");
        assertThat(service.calculateNetRealizableValueCost(material, List.of(), new BigDecimal("30"))).isEqualByComparingTo("180");
    }

    private StockBalance balance(UUID warehouse, String quantity, String value) {
        StockBalance balance = new StockBalance();
        balance.setMaterialId(material); balance.setWarehouseId(warehouse); balance.setCompanyId(company);
        balance.setQuantity(new BigDecimal(quantity)); balance.setTotalValue(new BigDecimal(value));
        return balance;
    }
}
