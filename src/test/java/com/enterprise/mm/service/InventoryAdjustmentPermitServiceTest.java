package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.repository.*;
import java.util.*;
import org.junit.jupiter.api.*;

class InventoryAdjustmentPermitServiceTest {
    @AfterEach void clear(){TenantContext.clear();CompanyContext.clear();}
    @Test void permitRequiresCurrentRevisionAndExactLineAndIsRemovedAfterFailure() {
        TenantContext.setTenant("default");CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        var revisions=mock(com.enterprise.mm.service.inventory.InventoryRevisionService.class);var items=mock(InventoryItemRepository.class);
        var permits=new InventoryAdjustmentPermitService(revisions,items,mock(InventoryScopeService.class));
        var document=new InventoryDocument();document.setId(UUID.randomUUID());document.setTenantId("default");document.setCompanyId(CompanyContext.currentCompany());document.setStatus("APPROVED");document.setCountRevision(4);document.setApprovedCountRevision(4L);document.setWarehouseId(UUID.randomUUID());
        var line=new InventoryItem();line.setId(UUID.randomUUID());line.setInventoryDocumentId(document.getId());line.setCompanyId(document.getCompanyId());line.setTenantId("default");line.setWarehouseId(document.getWarehouseId());line.setMaterialId(UUID.randomUUID());line.setStorageLocationId(UUID.randomUUID()); line.setSystemQuantity(java.math.BigDecimal.TEN); line.setCountedQuantity(new java.math.BigDecimal("7")); document.setPostingDate(java.time.LocalDate.now());
        when(revisions.lock(document.getId())).thenReturn(document);when(items.findByIdAndIsDeletedFalse(line.getId())).thenReturn(Optional.of(line));
        var movement=new StockMovement();movement.setCompanyId(document.getCompanyId());movement.setReferenceType("InventoryDocument");movement.setReferenceId(document.getId());movement.setSourceInventoryItemId(line.getId());movement.setWarehouseId(line.getWarehouseId());movement.setMaterialId(line.getMaterialId());movement.setStorageLocationId(line.getStorageLocationId());
        movement.setQuantity(new java.math.BigDecimal("3")); movement.setMovementType(com.enterprise.mm.enums.MovementType.WRITE_OFF); movement.setMovementDate(document.getPostingDate());
        assertThat(permits.allows(movement,document)).isFalse();
        assertThatThrownBy(()->permits.withApprovedCount(document.getId(),3,()->true)).hasMessageContaining("not approved");
        assertThatThrownBy(()->permits.withApprovedCount(document.getId(),4,()->{
            assertThat(permits.allows(movement,document)).isTrue();
            movement.setQuantity(java.math.BigDecimal.ONE);assertThat(permits.allows(movement,document)).isFalse();movement.setQuantity(new java.math.BigDecimal("3"));
            movement.setMovementType(com.enterprise.mm.enums.MovementType.ADJUSTMENT);assertThat(permits.allows(movement,document)).isFalse();movement.setMovementType(com.enterprise.mm.enums.MovementType.WRITE_OFF);
            CompanyContext.setCompany(UUID.randomUUID());assertThat(permits.allows(movement,document)).isFalse();CompanyContext.setCompany(document.getCompanyId());
            movement.setStorageLocationId(UUID.randomUUID());assertThat(permits.allows(movement,document)).isFalse();
            movement.setStorageLocationId(line.getStorageLocationId());throw new IllegalStateException("Posting failed");
        })).hasMessage("Posting failed");
        assertThat(permits.allows(movement,document)).isFalse();
    }
}
