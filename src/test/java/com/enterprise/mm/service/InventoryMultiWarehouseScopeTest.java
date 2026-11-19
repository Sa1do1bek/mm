package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.InventoryScopeMode;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.DepartmentWarehouseAccessRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.InventoryDocumentMaterialGroupRepository;
import com.enterprise.mm.repository.InventoryDocumentMaterialRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryMultiWarehouseScopeTest {
    private static final String TENANT = "inventory-multi-scope";
    private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID D1 = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID D2 = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID W1 = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID W2 = UUID.fromString("00000000-0000-0000-0000-000000000022");
    private static final UUID W3 = UUID.fromString("00000000-0000-0000-0000-000000000023");

    @Mock private DepartmentWarehouseAccessRepository departmentAccess;
    @Mock private DepartmentRepository departments;
    @Mock private WarehouseRepository warehouses;
    @Mock private RoleDataScopeService authority;
    @Mock private InventoryDocumentMaterialRepository selectedMaterials;
    @Mock private InventoryDocumentMaterialGroupRepository selectedGroups;
    @Mock private InventoryScopeLockService locks;
    @InjectMocks private InventoryScopeService scope;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void multipleDepartmentsResolveToDeduplicatedWarehouseUnion() {
        TenantContext.setTenant(TENANT);
        var document = document(InventoryScopeMode.DEPARTMENTS);
        document.setDepartmentIds(Set.of(D1, D2));
        when(departments.findByIdAndIsDeletedFalse(D1)).thenReturn(Optional.of(department(D1)));
        when(departments.findByIdAndIsDeletedFalse(D2)).thenReturn(Optional.of(department(D2)));
        when(departmentAccess.findActiveWarehouseIds(TENANT, COMPANY, D1)).thenReturn(Set.of(W1, W2));
        when(departmentAccess.findActiveWarehouseIds(TENANT, COMPANY, D2)).thenReturn(Set.of(W2, W3));
        for (UUID id : List.of(W1, W2, W3)) {
            when(warehouses.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(warehouse(id)));
            when(authority.canAccessWarehouse(id)).thenReturn(true);
        }

        scope.prepareScope(document);

        assertThat(document.getResolvedWarehouseIds()).containsExactlyInAnyOrder(W1, W2, W3);
        assertThat(scope.contains(document, W1, null, null, null)).isTrue();
        assertThat(scope.contains(document, W3, null, null, null)).isTrue();
        assertThat(scope.contains(document, UUID.randomUUID(), null, null, null)).isFalse();
    }

    @Test void warehouseModeRejectsDepartmentIds() {
        TenantContext.setTenant(TENANT);
        var document = document(InventoryScopeMode.WAREHOUSES);
        document.setDepartmentIds(Set.of(D1));
        document.setSelectedWarehouseIds(Set.of(W1, W2));

        assertThatThrownBy(() -> scope.prepareScope(document))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("departmentIds");
    }

    @Test void mixedSelectionUnitesMappedAndExplicitWarehousesWithoutDuplicates() {
        TenantContext.setTenant(TENANT);
        var document = document(InventoryScopeMode.MIXED);
        document.setDepartmentIds(Set.of(D1));
        document.setSelectedWarehouseIds(Set.of(W2, W3));
        when(departments.findByIdAndIsDeletedFalse(D1)).thenReturn(Optional.of(department(D1)));
        when(departmentAccess.findActiveWarehouseIds(TENANT, COMPANY, D1)).thenReturn(Set.of(W1, W2));
        for (UUID id : List.of(W1, W2, W3)) {
            when(warehouses.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(warehouse(id)));
            when(authority.canAccessWarehouse(id)).thenReturn(true);
        }

        scope.prepareScope(document);

        assertThat(document.getDepartmentIds()).containsExactly(D1);
        assertThat(document.getSelectedWarehouseIds()).containsExactlyInAnyOrder(W2, W3);
        assertThat(scope.warehouseIds(document)).containsExactly(W1, W2, W3);
    }

    @Test void multipleExplicitWarehousesStayWithinSelectedSet() {
        TenantContext.setTenant(TENANT);
        var document = document(InventoryScopeMode.WAREHOUSES);
        document.setSelectedWarehouseIds(Set.of(W1, W3));
        for (UUID id : List.of(W1, W3)) {
            when(warehouses.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(warehouse(id)));
            when(authority.canAccessWarehouse(id)).thenReturn(true);
        }

        scope.prepareScope(document);

        assertThat(document.getResolvedWarehouseIds()).containsExactlyInAnyOrder(W1, W3);
        assertThat(scope.includesWarehouse(document, W2)).isFalse();
    }

    @Test void departmentWithoutWarehousesCannotBecomeEmptyCount() {
        TenantContext.setTenant(TENANT);
        var document = document(InventoryScopeMode.DEPARTMENTS);
        document.setDepartmentIds(Set.of(D1));
        when(departments.findByIdAndIsDeletedFalse(D1)).thenReturn(Optional.of(department(D1)));
        when(departmentAccess.findActiveWarehouseIds(TENANT, COMPANY, D1)).thenReturn(Set.of());

        assertThatThrownBy(() -> scope.prepareScope(document))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("no active warehouses");
    }

    @Test void departmentOutsideCurrentCompanyIsRejectedBeforeMapping() {
        TenantContext.setTenant(TENANT);
        var document = document(InventoryScopeMode.MIXED);
        document.setDepartmentIds(Set.of(D1));
        var otherCompany = department(D1);
        otherCompany.setCompanyId(UUID.randomUUID());
        when(departments.findByIdAndIsDeletedFalse(D1)).thenReturn(Optional.of(otherCompany));

        assertThatThrownBy(() -> scope.prepareScope(document))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("outside inventory company");
    }

    @Test void multiWarehouseCountOverlapsLegacyWarehouseAndLocksEveryWarehouse() {
        TenantContext.setTenant(TENANT);
        var multi = document(InventoryScopeMode.WAREHOUSES);
        multi.setResolvedWarehouseIds(Set.of(W1, W2));
        var legacy = document(null);
        legacy.setWarehouseId(W2);
        when(selectedMaterials.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, multi.getId()))
            .thenReturn(List.of());
        when(selectedMaterials.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, legacy.getId()))
            .thenReturn(List.of());
        when(selectedGroups.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, multi.getId()))
            .thenReturn(List.of());
        when(selectedGroups.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, legacy.getId()))
            .thenReturn(List.of());

        assertThat(scope.overlaps(multi, legacy)).isTrue();
        scope.lock(multi);
        verify(locks).lock(COMPANY, List.of(W1, W2));
    }

    private InventoryDocument document(InventoryScopeMode mode) {
        var d = new InventoryDocument();
        d.setTenantId(TENANT);
        d.setCompanyId(COMPANY);
        d.setScopeMode(mode);
        d.setId(UUID.randomUUID());
        return d;
    }

    private Warehouse warehouse(UUID id) {
        var w = new Warehouse();
        w.setId(id);
        w.setTenantId(TENANT);
        w.setCompanyId(COMPANY);
        w.setStatus("ACTIVE");
        return w;
    }

    private Department department(UUID id) {
        var d = new Department();
        d.setId(id);
        d.setTenantId(TENANT);
        d.setCompanyId(COMPANY);
        d.setStatus("ACTIVE");
        return d;
    }
}
