package com.enterprise.mm.inventory.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.inventory.availability.enums.InventoryAvailabilityQueueKey;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter;
import com.enterprise.mm.inventory.availability.repository.InventoryAvailabilityQueryRepository;
import com.enterprise.mm.inventory.availability.service.InventoryAvailabilityWorkspaceService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryAvailabilityWorkspaceServiceTest {
    @Mock InventoryAvailabilityQueryRepository repository;
    @Mock com.enterprise.mm.service.RoleDataScopeService roleDataScopeService;
    @org.junit.jupiter.api.BeforeEach void currentScope() {
        org.mockito.Mockito.lenient().when(roleDataScopeService.warehouseReadScope(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> {
                java.util.Collection<UUID> ids=invocation.getArgument(1);
                UUID id=invocation.getArgument(0);
                return ids != null && !ids.isEmpty() ? new java.util.LinkedHashSet<>(ids) : id == null ? java.util.Set.of() : java.util.Set.of(id);
            });
    }
    @InjectMocks InventoryAvailabilityWorkspaceService service;

    @Test
    void queueDefinitionCarriesTheExactNormalizedDrilldownFilter() {
        UUID warehouseId = UUID.randomUUID();
        InventoryAvailabilityFilter input = new InventoryAvailabilityFilter(
            InventoryAvailabilityQueueKey.SHORTAGE, "  pump  ", warehouseId, null,
                null, null, null, null, null);
        when(repository.count(any())).thenReturn(4L);

        var definition = service.queueDefinition(input);

        assertThat(definition.key()).isEqualTo(InventoryAvailabilityQueueKey.SHORTAGE);
        assertThat(definition.count()).isEqualTo(4);
        assertThat(definition.appliedFilters().search()).isEqualTo("pump");
        assertThat(definition.appliedFilters().warehouseId()).isEqualTo(warehouseId);
        ArgumentCaptor<InventoryAvailabilityFilter> filter =
            ArgumentCaptor.forClass(InventoryAvailabilityFilter.class);
        verify(repository).count(filter.capture());
        assertThat(filter.getValue()).isEqualTo(definition.appliedFilters());
    }

    @Test
    void queueDefinitionAndNormalizePreservesAllFilterFields() {
        UUID warehouseId = UUID.randomUUID();
        UUID warehouseId2 = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        java.time.LocalDate asOf = java.time.LocalDate.of(2026, 10, 20);
        java.time.LocalDate from = java.time.LocalDate.of(2026, 10, 1);
        java.time.LocalDate to = java.time.LocalDate.of(2026, 10, 31);

        InventoryAvailabilityFilter input = new InventoryAvailabilityFilter(
            InventoryAvailabilityQueueKey.SHORTAGE,
            " search ",
            warehouseId,
            null,
            true,
            false,
            null,
            null,
            null,
            " MAT-001 ",
            " Valve Steel ",
            materialId,
            java.util.List.of(warehouseId, warehouseId2),
            asOf,
            from,
            to
        );
        when(repository.count(any())).thenReturn(10L);

        var definition = service.queueDefinition(input);

        var applied = definition.appliedFilters();
        assertThat(applied.materialCode()).isEqualTo("MAT-001");
        assertThat(applied.materialName()).isEqualTo("Valve Steel");
        assertThat(applied.materialId()).isEqualTo(materialId);
        assertThat(applied.warehouseIds()).containsExactly(warehouseId, warehouseId2);
        assertThat(applied.asOfDate()).isEqualTo(asOf);
        assertThat(applied.dateFrom()).isEqualTo(from);
        assertThat(applied.dateTo()).isEqualTo(to);
        assertThat(applied.search()).isEqualTo("search");
    }

    @Test
    void unauthorizedWarehouseInWarehouseIdsThrowsAccessDenied() {
        UUID allowedWarehouse = UUID.randomUUID();
        UUID unauthorizedWarehouse = UUID.randomUUID();

        when(roleDataScopeService.warehouseReadScope(null, java.util.List.of(allowedWarehouse, unauthorizedWarehouse)))
            .thenThrow(new org.springframework.security.access.AccessDeniedException("Warehouse assignment required"));

        InventoryAvailabilityFilter input = new InventoryAvailabilityFilter(
            InventoryAvailabilityQueueKey.SHORTAGE,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            java.util.List.of(allowedWarehouse, unauthorizedWarehouse),
            null,
            null,
            null
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.queueDefinition(input))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
            .hasMessageContaining("Warehouse assignment required");
    }
}
