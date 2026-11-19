package com.enterprise.mm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.WarehouseLayoutDtos.*;
import com.enterprise.mm.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WarehouseLayoutServiceTest {
    @Mock WarehouseRepository warehouses;
    @Mock WarehouseLayoutRepository layouts;
    @Mock StorageLocationRepository locations;
    @Mock StorageBinRepository bins;
    @Mock RoleDataScopeService scope;
    @Mock EntityManager entityManager;
    WarehouseLayoutService service;
    final UUID warehouseId = UUID.randomUUID();
    final Warehouse warehouse = new Warehouse();

    @BeforeEach void setup() {
        service = new WarehouseLayoutService(warehouses, layouts, locations, bins, scope, entityManager);
    }
    void warehouseFound() {
        when(warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId))
            .thenReturn(Optional.of(warehouse));
    }
    Item rack(String id, int rotation, double x, UUID location) {
        return new Item(id, Kind.RACK, "Rack A", x, 2, 5, 2, 4, rotation, location, null);
    }
    Layout layout(Item... items) { return new Layout(20, 12, 8, List.of(items)); }

    @Test void emptyLayoutIsScopedAndDoesNotCreateARecord() {
        warehouseFound();
        var result = service.get(warehouseId);
        assertNull(result.version());
        assertTrue(result.layout().items().isEmpty());
        verify(scope).assertWarehouseAccess(warehouseId);
        verify(layouts, never()).saveAndFlush(any());
    }
    @Test void missingOrForeignWarehouseCannotBeRead() {
        assertThrows(NotFoundException.class, () -> service.get(warehouseId));
        verifyNoInteractions(layouts);
    }
    @Test void savesUnderOwnerLockAndRejectsAStaleVersion() {
        warehouseFound();
        var existing = new WarehouseLayout(); existing.setVersion(3L); existing.setWarehouseId(warehouseId);
        when(layouts.findByTenantIdAndCompanyIdAndWarehouseId(anyString(), any(), eq(warehouseId))).thenReturn(Optional.of(existing));
        assertThrows(ConflictException.class, () -> service.update(warehouseId, new UpdateRequest(2L, layout())));
        verify(entityManager).lock(warehouse, LockModeType.PESSIMISTIC_WRITE);
        verify(layouts, never()).saveAndFlush(any());
        when(layouts.saveAndFlush(existing)).thenReturn(existing);
        var result = service.update(warehouseId, new UpdateRequest(3L, layout(rack("a", 0, 2, null))));
        assertEquals(1, result.layout().items().size());
        assertNotNull(result.updatedAt());
    }
    @Test void firstSaveSetsScopeAndRequiresNullVersion() {
        warehouseFound();
        assertThrows(ConflictException.class, () -> service.update(warehouseId, new UpdateRequest(0L, layout())));
        when(layouts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        service.update(warehouseId, new UpdateRequest(null, layout()));
        verify(layouts).saveAndFlush(argThat(value -> warehouseId.equals(value.getWarehouseId())
            && CompanyContext.currentCompany().equals(value.getCompanyId()) && TenantContext.currentTenant().equals(value.getTenantId())));
    }
    @Test void rejectsLocationFromAnotherWarehouse() {
        warehouseFound();
        UUID id = UUID.randomUUID();
        var location = new StorageLocation(); location.setWarehouseId(UUID.randomUUID());
        location.setTenantId(TenantContext.currentTenant()); location.setCompanyId(CompanyContext.currentCompany());
        when(locations.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(location));
        assertThrows(NotFoundException.class, () -> service.update(warehouseId, new UpdateRequest(null, layout(rack("a", 0, 2, id)))));
        verify(layouts, never()).saveAndFlush(any());
    }
    @Test void validatesRotatedBoundsUniqueIdsAndFiniteNumbers() {
        assertDoesNotThrow(() -> WarehouseLayoutService.validateGeometry(layout(rack("a", 90, 18, null))));
        assertThrows(BusinessException.class, () -> WarehouseLayoutService.validateGeometry(layout(rack("a", 0, 18, null))));
        assertThrows(BusinessException.class, () -> WarehouseLayoutService.validateGeometry(layout(rack("a", 45, 2, null))));
        assertThrows(BusinessException.class, () -> WarehouseLayoutService.validateGeometry(layout(rack("a", 0, 2, null), rack("a", 0, 3, null))));
        assertThrows(BusinessException.class, () -> WarehouseLayoutService.validateGeometry(layout(rack("a", 0, Double.NaN, null))));
    }
    @Test void requestValidationCascadesToItemsAndRejectsNullItems() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new UpdateRequest(null, layout(rack("a", 0, 2, null)))).isEmpty());
            assertFalse(validator.validate(new UpdateRequest(null, layout(rack("", 0, 2, null)))).isEmpty());
            assertFalse(validator.validate(new UpdateRequest(null, new Layout(20, 12, 8, Arrays.asList((Item) null)))).isEmpty());
            assertFalse(validator.validate(new UpdateRequest(null, new Layout(-1, 12, 8, List.of()))).isEmpty());
        }
    }
}
