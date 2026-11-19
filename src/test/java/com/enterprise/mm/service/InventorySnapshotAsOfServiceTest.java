package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.entity.InventoryDocumentMaterialGroup;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.repository.InventoryDocumentMaterialGroupRepository;
import com.enterprise.mm.repository.InventoryDocumentMaterialRepository;
import com.enterprise.mm.repository.InventoryItemRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InventorySnapshotAsOfServiceTest {
    @Mock InventoryStockAsOfQuery historical;
    @Mock StockBalanceRepository balances;
    @Mock InventoryItemRepository items;
    @Mock InventoryDocumentMaterialRepository selectedMaterials;
    @Mock InventoryDocumentMaterialGroupRepository selectedGroups;
    @Mock InventoryScopeService scope;
    @Mock MaterialCatalogGuard materialGuard;
    @InjectMocks InventoryDocumentService documents;

    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void physicalDifferenceUsesCurrentCaptureAndKeepsCutoffQuantitySeparately() {
        TenantContext.setTenant("as-of-service");
        UUID company = UUID.randomUUID(), warehouse = UUID.randomUUID(), material = UUID.randomUUID();
        CompanyContext.setCompany(company);
        var cutoff = LocalDate.now().minusDays(10);
        var document = new InventoryDocument();
        document.setId(UUID.randomUUID());
        document.setCompanyId(company);
        document.setWarehouseId(warehouse);
        document.setStockAsOfDate(cutoff);
        var balance = new StockBalance();
        balance.setId(UUID.randomUUID());
        balance.setCompanyId(company);
        balance.setWarehouseId(warehouse);
        balance.setMaterialId(material);
        balance.setQuantity(new BigDecimal("7"));
        balance.setTotalValue(new BigDecimal("70"));
        when(scope.warehouseIds(document)).thenReturn(List.of(warehouse));
        when(historical.historical("as-of-service", company, List.of(warehouse), cutoff))
            .thenReturn(Map.of(balance.getId(), new InventoryStockAsOfQuery.HistoricalBalance(new BigDecimal("10"), new BigDecimal("100"))));
        when(selectedMaterials.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(any(), eq(document.getId())))
            .thenReturn(List.of());
        when(selectedGroups.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(any(), eq(document.getId())))
            .thenReturn(List.of());
        when(balances.findInventorySnapshotBalances(eq("as-of-service"), eq(company), eq(false),
            eq(List.of(warehouse)), eq(null), eq(null), eq(true), any(), any(), eq(true)))
            .thenReturn(List.of(balance));

        ReflectionTestUtils.invokeMethod(documents, "createSnapshot", document);

        var saved = ArgumentCaptor.forClass(InventoryItem.class);
        verify(items).save(saved.capture());
        assertThat(saved.getValue().getSystemQuantity()).isEqualByComparingTo("7");
        assertThat(saved.getValue().getAsOfQuantity()).isEqualByComparingTo("10");
        assertThat(saved.getValue().getAsOfValue()).isEqualByComparingTo("100");
        assertThat(document.getSnapshotAt()).isNotNull();
    }

    @Test void globalGroupCountRestrictsBalanceQueryAndChecksMaterialOnce() {
        TenantContext.setTenant("as-of-service");
        UUID company = UUID.randomUUID(), group = UUID.randomUUID(), material = UUID.randomUUID();
        CompanyContext.setCompany(company);
        var document = new InventoryDocument();
        document.setId(UUID.randomUUID());
        document.setCompanyId(company);
        document.setGlobalWarehouse(true);
        var selectedGroup = new InventoryDocumentMaterialGroup();
        selectedGroup.setMaterialGroupId(group);
        when(selectedMaterials.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc("as-of-service", document.getId()))
            .thenReturn(List.of());
        when(selectedGroups.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc("as-of-service", document.getId()))
            .thenReturn(List.of(selectedGroup));
        var first = new StockBalance();
        first.setId(UUID.randomUUID());
        first.setCompanyId(company);
        first.setWarehouseId(UUID.randomUUID());
        first.setMaterialId(material);
        first.setQuantity(BigDecimal.ONE);
        var second = new StockBalance();
        second.setId(UUID.randomUUID());
        second.setCompanyId(company);
        second.setWarehouseId(UUID.randomUUID());
        second.setMaterialId(material);
        second.setQuantity(BigDecimal.ONE);
        when(balances.findInventorySnapshotBalances(eq("as-of-service"), eq(company), eq(true),
            eq(List.of(new UUID(0, 0))), eq(null), eq(null), eq(false),
            eq(List.of(new UUID(0, 0))), eq(List.of(group)), eq(false)))
            .thenReturn(List.of(first, second));

        ReflectionTestUtils.invokeMethod(documents, "createSnapshot", document);

        verify(items, times(2)).save(any(InventoryItem.class));
        verify(materialGuard, times(1)).requireActiveTmbMaterial(material, "materialId");
        assertThat(document.getSnapshotAt()).isNotNull();
    }
}
