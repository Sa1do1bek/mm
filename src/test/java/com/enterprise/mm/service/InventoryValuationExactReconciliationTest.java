package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockCostMovementRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryValuationExactReconciliationTest {
    @Mock StockBalanceRepository balances;
    @Mock InventoryCostLayerRepository layers;
    @Mock StockCostMovementRepository costs;
    @Mock StockMovementRepository movements;
    @Mock StockBatchRepository batches;
    private InventoryValuationReconciliationService service;
    private UUID company, material, warehouse, batchId;
    private StockBalance balance;
    private InventoryCostLayer layer;
    private StockBatch batch;

    @BeforeEach void setUp() {
        TenantContext.setTenant("exact-reconciliation"); company=UUID.randomUUID(); CompanyContext.setCompany(company);
        material=UUID.randomUUID(); warehouse=UUID.randomUUID(); batchId=UUID.randomUUID();
        service = new InventoryValuationReconciliationService(balances,layers,costs,movements,batches);
        balance=new StockBalance(); balance.setId(UUID.randomUUID()); balance.setCompanyId(company);
        balance.setMaterialId(material); balance.setWarehouseId(warehouse); balance.setStockBatchId(batchId);
        balance.setQuantity(new BigDecimal("1.1234567")); balance.setTotalValue(BigDecimal.ZERO);
        layer=new InventoryCostLayer(); layer.setId(UUID.randomUUID()); layer.setCompanyId(company);
        layer.setMaterialId(material); layer.setWarehouseId(warehouse); layer.setStockBatchId(batchId);
        layer.setStatus("ACTIVE"); layer.setRemainingQuantity(balance.getQuantity()); layer.setTotalCost(BigDecimal.ZERO); layer.setAuthoritativeValue(true);
        batch=new StockBatch(); batch.setId(batchId); batch.setCompanyId(company); batch.setMaterialId(material); batch.setWarehouseId(warehouse);
        batch.setQuantity(balance.getQuantity()); batch.setFinalUnitCost(BigDecimal.ZERO);
        when(balances.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(),company)).thenReturn(List.of(balance));
        when(layers.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(),company)).thenReturn(List.of(layer));
        when(batches.findByTenantIdAndCompanyIdAndQuantityGreaterThanAndIsDeletedFalse(TenantContext.currentTenant(),company,BigDecimal.ZERO)).thenReturn(List.of(batch));
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void approvedExactZeroBookValueReconciles() {
        assertThat(service.run(warehouse)).isEmpty();
    }
    @Test void unapprovedZeroBookValueRemainsBlocked() {
        layer.setAuthoritativeValue(false);
        assertThat(service.run(warehouse)).extracting(i->i.issueType()).contains("ZERO_COST_STOCK","BATCH_LAYER_MISMATCH");
    }
    @Test void sevenPlaceQuantityDifferenceIsReportedWithoutRoundingItsEvidence() {
        layer.setRemainingQuantity(new BigDecimal("1.1234566"));
        var issues=service.run(warehouse);
        assertThat(issues).extracting(i->i.issueType()).contains("ZERO_COST_STOCK","QUANTITY_MISMATCH","BATCH_LAYER_QUANTITY_MISMATCH");
        var mismatch=issues.stream().filter(i->"QUANTITY_MISMATCH".equals(i.issueType())).findFirst().orElseThrow();
        assertThat(mismatch.balanceQuantity()).isEqualByComparingTo("1.1234567");
        assertThat(mismatch.layerQuantity()).isEqualByComparingTo("1.1234566");
    }
    @Test void costEvidenceCannotExemptAnotherBatchOrWarehouse() {
        layer.setStockBatchId(UUID.randomUUID());
        assertThat(service.run(warehouse)).extracting(i->i.issueType()).contains("ZERO_COST_STOCK","BATCH_LAYER_MISMATCH");
        layer.setStockBatchId(batchId); layer.setWarehouseId(UUID.randomUUID());
        assertThat(service.run(warehouse)).extracting(i->i.issueType()).contains("ZERO_COST_STOCK","BATCH_LAYER_MISMATCH");
    }
    @Test void aPositiveExactBookValueDoesNotUseRoundedUnitCostForBatchReconciliation() {
        balance.setQuantity(new BigDecimal("651800.1234567")); balance.setTotalValue(new BigDecimal("1.01"));
        layer.setRemainingQuantity(balance.getQuantity()); layer.setTotalCost(balance.getTotalValue());
        batch.setQuantity(balance.getQuantity()); batch.setFinalUnitCost(new BigDecimal("0.000002"));
        assertThat(service.run(warehouse)).isEmpty();
        balance.setTotalValue(new BigDecimal("1.04"));
        assertThat(service.run(warehouse)).extracting(i->i.issueType()).contains("VALUE_MISMATCH","BATCH_LAYER_VALUE_MISMATCH");
    }
    @Test void tinyPositiveAuthoritativeValueMayHaveAZeroDisplayUnitCost() {
        balance.setQuantity(new BigDecimal("651800")); balance.setTotalValue(new BigDecimal("0.01"));
        layer.setRemainingQuantity(balance.getQuantity()); layer.setTotalCost(balance.getTotalValue());
        batch.setQuantity(balance.getQuantity()); batch.setFinalUnitCost(BigDecimal.ZERO);
        assertThat(service.run(warehouse)).isEmpty();
    }
    @Test void inactiveCostEvidenceCannotApproveZeroStock() {
        layer.setStatus("INACTIVE");
        assertThat(service.run(warehouse)).extracting(i->i.issueType()).contains("ZERO_COST_STOCK","MISSING_COST_LAYER","BATCH_LAYER_MISMATCH");
    }
    @Test void aZeroCostBatchSplitAcrossPositionsUsesCompleteExactCoverage() {
        balance.setQuantity(new BigDecimal("0.1234567"));
        var second=new StockBalance(); second.setId(UUID.randomUUID()); second.setCompanyId(company); second.setMaterialId(material);
        second.setWarehouseId(warehouse); second.setStockBatchId(batchId); second.setQuantity(BigDecimal.ONE); second.setTotalValue(BigDecimal.ZERO);
        when(balances.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(),company)).thenReturn(List.of(balance,second));
        assertThat(service.run(warehouse)).isEmpty();
    }
}
