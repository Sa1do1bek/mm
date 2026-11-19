package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockValuationCostSelectionServiceTest {
    @Mock
    private StockValuationService valuationService;
    @Mock
    private InventoryValuationPolicyService policyService;
    @Mock
    private StockBalanceRepository stockBalanceRepository;
    @Mock
    private MaterialRepository materialRepository;
    @Mock
    private WarehouseRepository warehouseRepository;

    @Test
    void multiWarehouseGroupsUseNetStockAndOmitExhaustedGroups() {
        UUID materialId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(balance(materialId, first, "10"), balance(materialId, first, "-2"),
                balance(materialId, second, "5"), balance(materialId, second, "-5")));
        var groups = service().findPositiveStockGroupsForWarehouses(List.of(first, second));
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().warehouseId()).isEqualTo(first);
        assertThat(groups.getFirst().quantity()).isEqualByComparingTo("8");
    }
    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void calculateStockCostsGroupsPositiveBalancesAndPreservesResponseShape() {
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Material material = material(materialId, "Steel");
        Warehouse warehouse = warehouse(warehouseId, "Main");
        when(materialRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(material));
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(warehouse));
        when(stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(balance(materialId, warehouseId, "2"), balance(materialId, warehouseId, "3"), balance(materialId, warehouseId, "-1")));
        when(policyService.resolveMethod(materialId, warehouseId)).thenReturn(ValuationMethod.WEIGHTED_AVERAGE);
        when(valuationService.calculateWeightedAverageCost(materialId, java.util.List.of(warehouseId), new BigDecimal("4"))).thenReturn(new BigDecimal("20.0000"));

        StockValuationCostSelectionService service = service();

        List<StockValuationSelectedStocksResponse.StockCost> result = service.calculateStockCosts(null);

        assertThat(result).containsExactly(new StockValuationSelectedStocksResponse.StockCost(
            materialId, "Steel", warehouseId, "Main", new BigDecimal("4"), com.enterprise.mm.enums.ValuationMethod.WEIGHTED_AVERAGE,
            new BigDecimal("20.0000"), true, null));
        verify(stockBalanceRepository).findByTenantIdAndCompanyIdAndIsDeletedFalse(eq(TenantContext.currentTenant()), eq(CompanyContext.currentCompany()));
    }

    @Test
    void calculateStockCostsFiltersByWarehouse() {
        UUID materialId = UUID.randomUUID();
        UUID selectedWarehouseId = UUID.randomUUID();
        UUID otherWarehouseId = UUID.randomUUID();
        when(materialRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(material(materialId, "Steel")));
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(warehouse(selectedWarehouseId, "Main"), warehouse(otherWarehouseId, "Overflow")));
        when(stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(balance(materialId, selectedWarehouseId, "2"), balance(materialId, otherWarehouseId, "4")));
        when(policyService.resolveMethod(materialId, selectedWarehouseId)).thenReturn(ValuationMethod.WEIGHTED_AVERAGE);
        when(valuationService.calculateWeightedAverageCost(materialId, java.util.List.of(selectedWarehouseId), new BigDecimal("2"))).thenReturn(new BigDecimal("10.0000"));

        List<StockValuationSelectedStocksResponse.StockCost> result = service().calculateStockCosts(selectedWarehouseId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().warehouseId()).isEqualTo(selectedWarehouseId);
        assertThat(result.getFirst().quantity()).isEqualByComparingTo("2");
    }

    /**
     * TODO item 4 (action-prefill / "Оценка"): the drawer's valuation action needs to
     * open scoped to exactly the material the operator picked. Before the materialId
     * overload existed, calculateStockCosts could only be scoped by warehouse, so a
     * request for one material's cost still returned every other material sharing that
     * warehouse.
     */
    @Test
    void calculateStockCostsFiltersByMaterialWhenMaterialIdIsProvided() {
        UUID targetMaterialId = UUID.randomUUID();
        UUID otherMaterialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(materialRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
                .thenReturn(List.of(material(targetMaterialId, "Steel"), material(otherMaterialId, "Copper")));
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
                .thenReturn(List.of(warehouse(warehouseId, "Main")));
        when(stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
                .thenReturn(List.of(balance(targetMaterialId, warehouseId, "2"), balance(otherMaterialId, warehouseId, "4")));
        when(policyService.resolveMethod(targetMaterialId, warehouseId)).thenReturn(ValuationMethod.WEIGHTED_AVERAGE);
        when(valuationService.calculateWeightedAverageCost(targetMaterialId, java.util.List.of(warehouseId), new BigDecimal("2")))
                .thenReturn(new BigDecimal("6.0000"));

        List<StockValuationSelectedStocksResponse.StockCost> result =
                service().calculateStockCosts(null, targetMaterialId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().materialId()).isEqualTo(targetMaterialId);
        assertThat(result.getFirst().materialName()).isEqualTo("Steel");
    }

    @Test
    void calculateStockCostsUsesRequestedMethodInsteadOfMaterialPolicy() {
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(materialRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(material(materialId, "Steel")));
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(warehouse(warehouseId, "Main")));
        when(stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(balance(materialId, warehouseId, "2")));
        when(valuationService.calculateFifoCost(materialId, java.util.List.of(warehouseId), new BigDecimal("2"))).thenReturn(new BigDecimal("11.0000"));

        StockValuationSelectedStocksResponse.StockCost result = service()
            .calculateStockCosts(null, ValuationMethod.FIFO)
            .getFirst();

        assertThat(result.valuationMethod()).isEqualTo(com.enterprise.mm.enums.ValuationMethod.FIFO);
        assertThat(result.totalCost()).isEqualByComparingTo("11.0000");
        verify(valuationService).calculateFifoCost(materialId, java.util.List.of(warehouseId), new BigDecimal("2"));
    }

    @Test
    void calculateStockCostsConvertsRowCostFailureIntoUnavailableRow() {
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(materialRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(material(materialId, "Steel")));
        when(warehouseRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(warehouse(warehouseId, "Main")));
        when(stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()))
            .thenReturn(List.of(balance(materialId, warehouseId, "2")));
        when(policyService.resolveMethod(materialId, warehouseId)).thenReturn(ValuationMethod.FIFO);
        when(valuationService.calculateFifoCost(materialId, java.util.List.of(warehouseId), new BigDecimal("2")))
            .thenThrow(new IllegalStateException("Not enough stock batches"));

        StockValuationSelectedStocksResponse.StockCost result = service().calculateStockCosts(null).getFirst();

        assertThat(result.available()).isFalse();
        assertThat(result.totalCost()).isNull();
        assertThat(result.errorMessage()).isEqualTo("Not enough stock batches");
        assertThat(result.valuationMethod()).isEqualTo(com.enterprise.mm.enums.ValuationMethod.FIFO);
    }

    private StockValuationCostSelectionService service() {
        return new StockValuationCostSelectionService(
            valuationService, policyService, stockBalanceRepository, materialRepository, warehouseRepository);
    }

    private static Material material(UUID id, String name) {
        Material material = new Material();
        material.setId(id);
        material.setName(name);
        return material;
    }

    private static Warehouse warehouse(UUID id, String name) {
        Warehouse warehouse = new Warehouse();
        warehouse.setId(id);
        warehouse.setName(name);
        return warehouse;
    }

    private static StockBalance balance(UUID materialId, UUID warehouseId, String quantity) {
        StockBalance balance = new StockBalance();
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setQuantity(new BigDecimal(quantity));
        balance.setAvailableQuantity(new BigDecimal(quantity));
        return balance;
    }
}
