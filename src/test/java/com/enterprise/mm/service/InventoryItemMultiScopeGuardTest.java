package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.enums.InventoryScopeMode;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.inventory.policy.InventoryLineMutationPolicy;
import com.enterprise.mm.payload.InventoryItemCreateRequest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryItemMultiScopeGuardTest {
    @Mock com.enterprise.mm.service.inventory.InventoryAccessService access;
    @Mock MaterialCatalogGuard materials;
    @Mock InventoryLineMutationPolicy policy;
    @Mock InventoryScopeService scope;
    @InjectMocks InventoryItemService items;

    @Test void manualLineOutsideResolvedWarehouseSetIsRejected() {
        UUID countId = UUID.randomUUID(), allowed = UUID.randomUUID(), outside = UUID.randomUUID(), material = UUID.randomUUID();
        var count = new InventoryDocument();
        count.setId(countId);
        count.setStatus("IN_PROGRESS");
        count.setScopeMode(InventoryScopeMode.WAREHOUSES);
        count.setResolvedWarehouseIds(Set.of(allowed));
        when(access.document(countId)).thenReturn(count);
        var line = new InventoryItem();
        line.setInventoryDocumentId(countId);
        line.setMaterialId(material);
        line.setWarehouseId(outside);

        assertThatThrownBy(() -> items.beforeCreate(line, new InventoryItemCreateRequest()))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("outside the count scope");
    }
}
