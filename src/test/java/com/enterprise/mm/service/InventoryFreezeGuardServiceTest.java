package com.enterprise.mm.service;

import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.InventoryDocumentRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InventoryFreezeGuardServiceTest {
    @Test void anExternalReferenceStringCannotBypassTheStockFreeze() {
        var locks=mock(InventoryScopeLockService.class);var scopes=mock(InventoryScopeService.class);var permits=mock(InventoryAdjustmentPermitService.class);
        var frozen=new com.enterprise.mm.entity.InventoryDocument();
        when(scopes.active(any())).thenReturn(java.util.List.of(frozen));when(scopes.contains(any(),any(),any(),any(),any())).thenReturn(true);
        var guard=new InventoryFreezeGuardService(locks,scopes,permits);
        var movement=new StockMovement();movement.setCompanyId(UUID.randomUUID());movement.setWarehouseId(UUID.randomUUID());
        movement.setMaterialId(UUID.randomUUID());movement.setReferenceType("InventoryDocument");
        assertThatThrownBy(()->guard.ensureMovementAllowed(movement)).isInstanceOf(BusinessException.class);
    }
}
