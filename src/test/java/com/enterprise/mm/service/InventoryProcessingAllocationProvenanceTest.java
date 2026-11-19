package com.enterprise.mm.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.inventory.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class InventoryProcessingAllocationProvenanceTest {
    @AfterEach void clear(){TenantContext.clear();CompanyContext.clear();}
    @Test void positiveAdjustmentPreservesCompanyExactStockKeySourceLineAndExplicitPostingDate() {
        TenantContext.setTenant("test");CompanyContext.setCompany(UUID.randomUUID());
        var d=new InventoryDocument();d.setId(UUID.randomUUID());d.setTenantId("test");d.setCompanyId(CompanyContext.currentCompany());d.setStatus("APPROVED");d.setVersion(3L);d.setCountRevision(8);d.setApprovedCountRevision(8L);
        var line=new InventoryItem();line.setId(UUID.randomUUID());line.setInventoryDocumentId(d.getId());line.setTenantId(d.getTenantId());line.setCompanyId(d.getCompanyId());line.setMaterialId(UUID.randomUUID());line.setWarehouseId(UUID.randomUUID());line.setStorageLocationId(UUID.randomUUID());line.setStorageBinId(UUID.randomUUID());line.setStockBatchId(UUID.randomUUID());line.setSystemQuantity(BigDecimal.TEN);line.setCountedQuantity(new BigDecimal("12"));line.setUnitCost(new BigDecimal("6"));
        var docs=mock(InventoryDocumentRepository.class);var items=mock(InventoryItemRepository.class);var stock=mock(StockMovementService.class);var movements=mock(StockMovementRepository.class);var revisions=mock(InventoryRevisionService.class);var ready=mock(InventoryPostingReadinessService.class);var periods=mock(InventoryPeriodGuardService.class);
        when(revisions.lock(d.getId())).thenReturn(d);when(items.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse("test",d.getId())).thenReturn(List.of(line));
        var posted=new StockMovementResponse();posted.setId(UUID.randomUUID());when(stock.createAndPostManagedInventoryAdjustment(any())).thenReturn(posted);
        var service=service(docs,items,stock,movements,revisions,ready,periods);
        var date=LocalDate.of(2026,9,18);var result=service.postAdjustments(d.getId(),new InventoryAdjustmentPostRequest(3L,8L,date),"post-1");
        var argument=ArgumentCaptor.forClass(StockMovement.class);verify(stock).createAndPostManagedInventoryAdjustment(argument.capture());var m=argument.getValue();
        assertThat(m.getCompanyId()).isEqualTo(d.getCompanyId());assertThat(m.getTenantId()).isEqualTo(d.getTenantId());assertThat(m.getSourceInventoryItemId()).isEqualTo(line.getId());assertThat(m.getReferenceId()).isEqualTo(d.getId());
        assertThat(m.getWarehouseId()).isEqualTo(line.getWarehouseId());assertThat(m.getStorageLocationId()).isEqualTo(line.getStorageLocationId());assertThat(m.getStorageBinId()).isEqualTo(line.getStorageBinId());assertThat(m.getStockBatchId()).isEqualTo(line.getStockBatchId());assertThat(m.getQuantity()).isEqualByComparingTo("2");assertThat(m.getMovementDate()).isEqualTo(date);
        assertThat(line.getLinkedStockMovementId()).isEqualTo(posted.getId());assertThat(result.movementIds()).containsExactly(posted.getId());assertThat(result.approvedCountRevision()).isEqualTo(8);assertThat(result.postingDate()).isEqualTo(date);assertThat(result.alreadyPosted()).isFalse();
        verify(ready).requireReady(d,date);verify(periods).ensureOpen(line.getWarehouseId(),date,"inventory adjustment");
    }
    @Test void missingDateFailsBeforeAnyStockMutation() {
        var d=new InventoryDocument();d.setId(UUID.randomUUID());var revisions=mock(InventoryRevisionService.class);when(revisions.lock(d.getId())).thenReturn(d);var stock=mock(StockMovementService.class);
        var service=service(mock(InventoryDocumentRepository.class),mock(InventoryItemRepository.class),stock,mock(StockMovementRepository.class),revisions,mock(InventoryPostingReadinessService.class),mock(InventoryPeriodGuardService.class));
        assertThatThrownBy(()->service.postAdjustments(d.getId(),new InventoryAdjustmentPostRequest(1L,1L,null),"missing-date")).hasMessageContaining("explicit posting date");verifyNoInteractions(stock);
    }
    @SuppressWarnings("unchecked")
    private InventoryProcessingService service(InventoryDocumentRepository docs,InventoryItemRepository items,StockMovementService stock,StockMovementRepository movements,InventoryRevisionService revisions,InventoryPostingReadinessService ready,InventoryPeriodGuardService periods) {
        var idem=mock(IdempotencyService.class);when(idem.execute(any(),any(),any(),any(),any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(5)).get());
        var permit=mock(InventoryAdjustmentPermitService.class);when(permit.withApprovedCount(any(),anyLong(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(2)).get());
        var materials=mock(MaterialRepository.class);
        when(materials.findByIdAndIsDeletedFalse(any())).thenAnswer(invocation -> {
            var material = new Material();
            material.setId(invocation.getArgument(0));
            material.setMaterialType(MaterialType.TMB);
            material.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);
            return Optional.of(material);
        });
        return new InventoryProcessingService(docs,items,stock,movements,mock(StockBatchRepository.class),mock(AuditService.class),idem,permit,revisions,ready,mock(InventoryScopeLockService.class),mock(InventoryScopeService.class),mock(InventoryFoundStockService.class),periods,materials,mock(MaterialUnitEligibilityService.class),mock(StockValuationService.class));
    }
}
