package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.inventory.allocation.*;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class DamagedStockWriteOffTest {
    final UUID company = UUID.randomUUID(), materialId = UUID.randomUUID(), warehouse = UUID.randomUUID();
    final UUID balanceId = UUID.randomUUID(), batchId = UUID.randomUUID(), caseId = UUID.randomUUID(), movementId = UUID.randomUUID();
    final StockMovementRepository movements = mock(StockMovementRepository.class);
    final StockBalanceRepository balances = mock(StockBalanceRepository.class);
    final StockBatchRepository batches = mock(StockBatchRepository.class);
    final MaterialRepository materials = mock(MaterialRepository.class);
    final MaterialCatalogGuard catalog = mock(MaterialCatalogGuard.class);
    final InventoryCostingService costing = mock(InventoryCostingService.class);
    final InventoryValuationPolicyService valuation = mock(InventoryValuationPolicyService.class);
    final StockMovementAllocationWriter allocations = mock(StockMovementAllocationWriter.class);
    final StockMovementService service = new StockMovementService(movements, new StockMovementMapper(), balances, batches,
        materials, catalog, mock(WarehouseRepository.class), new StockBalanceMutationService(), mock(InventoryPeriodGuardService.class),
        valuation, mock(StockValuationService.class), costing, allocations, mock(StockMovementAllocationRepository.class),
        mock(StockMovementAllocationCostLayerRepository.class), mock(AuditService.class), mock(RoleDataScopeService.class),
        mock(InventoryFreezeGuardService.class), false);
    StockBalance balance; StockBatch batch; DamagedStockCase damage; Material material;

    @BeforeEach void setup() {
        TenantContext.setTenant("damage-test"); CompanyContext.setCompany(company);
        var unit = new UnitOfMeasure(); unit.setId(UUID.randomUUID()); unit.setCode("EA"); unit.setStockQuantityScale(0);
        material = new Material(); material.setId(materialId); material.setTenantId("damage-test"); material.setCompanyId(company); material.setMaterialType(MaterialType.TMB);
        material.setUnitOfMeasure(unit); material.setUnitOfMeasureId(unit.getId());
        balance = new StockBalance(); balance.setId(balanceId); balance.setTenantId("damage-test"); balance.setCompanyId(company);
        balance.setMaterialId(materialId); balance.setWarehouseId(warehouse); balance.setStockBatchId(batchId);
        balance.setQuantity(new BigDecimal("10")); balance.setAvailableQuantity(new BigDecimal("7"));
        balance.setQuarantineQuantity(new BigDecimal("3")); balance.setReservedQuantity(BigDecimal.ZERO); balance.setTotalValue(new BigDecimal("100"));
        batch = new StockBatch(); batch.setId(batchId); batch.setTenantId("damage-test"); batch.setCompanyId(company);
        batch.setMaterialId(materialId); batch.setWarehouseId(warehouse); batch.setBatchNumber("LOT-1"); batch.setQuantity(new BigDecimal("10"));
        damage = new DamagedStockCase(); damage.setId(caseId); damage.setTenantId("damage-test"); damage.setCompanyId(company);
        damage.setBalanceId(balanceId); damage.setWarehouseId(warehouse); damage.setStatus("APPROVED"); damage.setQuantity(new BigDecimal("2")); damage.setDescription("Damage");
        lenient().when(catalog.requireActiveTmbMaterial(materialId, "materialId")).thenReturn(material);
        lenient().when(materials.findByIdAndIsDeletedFalse(materialId)).thenReturn(Optional.of(material));
        lenient().when(movements.save(any())).thenAnswer(inv -> { StockMovement m = inv.getArgument(0); m.setId(movementId); return m; });
        lenient().when(balances.findByIdAndIsDeletedFalseForUpdate(balanceId)).thenReturn(Optional.of(balance));
        lenient().when(balances.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(batches.findByIdAndIsDeletedFalseForUpdate(batchId)).thenReturn(Optional.of(batch));
        lenient().when(batches.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(valuation.resolveMethod(materialId, warehouse)).thenReturn(ValuationMethod.FIFO);
        lenient().when(valuation.roundingScale()).thenReturn(6); lenient().when(valuation.roundingMode()).thenReturn(RoundingMode.HALF_UP);
        var layer = new InventoryCostingService.LayerConsumption(UUID.randomUUID(), null, batchId, UUID.randomUUID(),
            new BigDecimal("2"), new BigDecimal("10"), new BigDecimal("20"), "UZS", BigDecimal.ONE);
        lenient().when(costing.consumeOutboundLayersForStockBatch(any(), eq(warehouse), eq(new BigDecimal("2")), eq(ValuationMethod.FIFO)))
            .thenReturn(new InventoryCostingService.CostResult(new BigDecimal("10"), new BigDecimal("20"), List.of(layer)));
    }
    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void postsExactQuarantinedQuantityAndCostWithAllocationEvidence() {
        assertThat(service.postDamagedStockWriteOff(damage, balance, LocalDate.now())).isEqualTo(movementId);
        assertThat(balance.getQuantity()).isEqualByComparingTo("8");
        assertThat(balance.getAvailableQuantity()).isEqualByComparingTo("7");
        assertThat(balance.getQuarantineQuantity()).isEqualByComparingTo("1");
        assertThat(balance.getTotalValue()).isEqualByComparingTo("80");
        assertThat(batch.getQuantity()).isEqualByComparingTo("8");
        var captured = ArgumentCaptor.forClass(StockMovementAllocationEvidence.class);
        verify(allocations).write(any(), captured.capture());
        var entry = captured.getValue().allocations().getFirst();
        assertThat(entry.sourceStockKey().disposition()).isEqualTo("QUARANTINE");
        assertThat(entry.onHandDelta()).isEqualByComparingTo("-2");
        assertThat(entry.quarantineDelta()).isEqualByComparingTo("-2");
        assertThat(entry.availableDelta()).isEqualByComparingTo("0");
        assertThat(entry.totalValueDelta()).isEqualByComparingTo("-20");
        assertThat(entry.costLineages()).hasSize(1);
    }
    @Test void rejectsInsufficientQuarantineWithoutMutatingBalance() {
        balance.setQuarantineQuantity(BigDecimal.ONE);
        assertThatThrownBy(() -> service.postDamagedStockWriteOff(damage, balance, LocalDate.now())).isInstanceOf(BusinessException.class);
        assertThat(balance.getQuantity()).isEqualByComparingTo("10");
        verifyNoInteractions(allocations);
    }
    @Test void rejectsUnapprovedOrForeignCaseBeforeCosting() {
        damage.setStatus("QUARANTINED");
        assertThatThrownBy(() -> service.postDamagedStockWriteOff(damage, balance, LocalDate.now())).isInstanceOf(BusinessException.class);
        damage.setStatus("APPROVED"); damage.setCompanyId(UUID.randomUUID());
        assertThatThrownBy(() -> service.postDamagedStockWriteOff(damage, balance, LocalDate.now())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(costing, allocations);
    }
}
