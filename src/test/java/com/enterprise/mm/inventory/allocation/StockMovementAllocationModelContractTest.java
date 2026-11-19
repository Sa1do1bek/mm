package com.enterprise.mm.inventory.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementAllocation;
import com.enterprise.mm.entity.StockMovementAllocationCostLayer;
import com.enterprise.mm.repository.StockMovementAllocationCostLayerRepository;
import com.enterprise.mm.repository.StockMovementAllocationRepository;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Query;
import org.hibernate.annotations.UuidGenerator;

class StockMovementAllocationModelContractTest {

    @Test
    void allocationEntitiesMatchDedicatedAppendOnlyTablesWithoutBusinessEntityColumns() throws Exception {
        assertThat(BusinessEntity.class.isAssignableFrom(StockMovementAllocation.class)).isFalse();
        assertThat(BusinessEntity.class.isAssignableFrom(StockMovementAllocationCostLayer.class)).isFalse();
        assertThat(StockMovementAllocation.class.getAnnotation(Table.class).name())
            .isEqualTo("stock_movement_allocations");
        assertThat(StockMovementAllocationCostLayer.class.getAnnotation(Table.class).name())
            .isEqualTo("stock_movement_allocation_cost_layers");

        assertAppendOnlyColumns(StockMovementAllocation.class);
        assertAppendOnlyColumns(StockMovementAllocationCostLayer.class);
        assertGeneratedUuid(StockMovementAllocation.class);
        assertGeneratedUuid(StockMovementAllocationCostLayer.class);

        assertColumns(StockMovementAllocation.class, List.of(
            "id", "tenant_id", "company_id", "created_at", "created_by", "movement_id",
            "allocation_sequence", "allocation_role", "material_id", "balance_id",
            "source_warehouse_id_snapshot", "source_storage_location_id_snapshot",
            "source_storage_bin_id_snapshot", "source_stock_batch_id_snapshot",
            "source_batch_number_snapshot", "source_disposition_snapshot",
            "target_warehouse_id_snapshot", "target_storage_location_id_snapshot",
            "target_storage_bin_id_snapshot", "target_stock_batch_id_snapshot",
            "target_batch_number_snapshot", "target_disposition_snapshot", "uom_id_snapshot",
            "uom_code_snapshot", "on_hand_delta", "available_delta", "reserved_delta",
            "quarantine_delta", "total_value_delta", "batch_quantity_delta", "unit_cost_snapshot",
            "currency_code_snapshot", "exchange_rate_snapshot", "source_inventory_item_id"));
        assertColumns(StockMovementAllocationCostLayer.class, List.of(
            "id", "tenant_id", "company_id", "created_at", "created_by", "allocation_id",
            "lineage_sequence", "lineage_role", "cost_layer_id", "parent_cost_layer_id_snapshot",
            "source_cost_movement_id", "quantity_delta", "value_delta", "unit_cost_snapshot",
            "currency_code_snapshot", "exchange_rate_snapshot"));
    }

    @Test
    void repositoriesExposeOnlyOrderedTenantCompanyMovementScopedReads() throws Exception {
        assertThat(CrudRepository.class.isAssignableFrom(StockMovementAllocationRepository.class)).isFalse();
        assertThat(CrudRepository.class.isAssignableFrom(StockMovementAllocationCostLayerRepository.class)).isFalse();

        Method allocations = StockMovementAllocationRepository.class.getMethod(
            "findByTenantIdAndCompanyIdAndMovementId",
            String.class,
            UUID.class,
            UUID.class
        );
        assertThat(allocations.getReturnType()).isEqualTo(List.class);
        assertThat(allocations.getAnnotation(Query.class).value())
            .contains("allocation.tenantId = :tenantId")
            .contains("allocation.companyId = :companyId")
            .contains("allocation.movementId = :movementId")
            .contains("order by allocation.allocationSequence, allocation.id");

        Method lineages = StockMovementAllocationCostLayerRepository.class.getMethod(
            "findByTenantIdAndCompanyIdAndMovementId",
            String.class,
            UUID.class,
            UUID.class
        );
        Query query = lineages.getAnnotation(Query.class);
        assertThat(query).isNotNull();
        assertThat(query.value())
            .contains("allocation.tenantId = :tenantId")
            .contains("allocation.companyId = :companyId")
            .contains("allocation.movementId = :movementId")
            .contains(
                "order by allocation.allocationSequence, allocation.id",
                "lineage.lineageSequence, lineage.id");
    }

    @Test
    void stockMovementMapsEveryV171SnapshotColumn() {
        assertColumn(StockMovement.class, "targetStockBatchId", "target_stock_batch_id");
        assertColumn(StockMovement.class, "batchNumberSnapshot", "batch_number_snapshot");
        assertColumn(StockMovement.class, "targetBatchNumberSnapshot", "target_batch_number_snapshot");
        assertColumn(StockMovement.class, "uomIdSnapshot", "uom_id_snapshot");
        assertColumn(StockMovement.class, "uomCodeSnapshot", "uom_code_snapshot");
        assertColumn(StockMovement.class, "sourceDispositionSnapshot", "source_disposition_snapshot");
        assertColumn(StockMovement.class, "targetDispositionSnapshot", "target_disposition_snapshot");
        assertColumn(StockMovement.class, "sourceInventoryItemId", "source_inventory_item_id");
    }

    private void assertAppendOnlyColumns(Class<?> entityType) {
        assertThat(entityType.getDeclaredFields())
            .filteredOn(field -> field.isAnnotationPresent(Column.class))
            .allSatisfy(field -> assertThat(field.getAnnotation(Column.class).updatable())
                .as("%s.%s must be immutable", entityType.getSimpleName(), field.getName())
                .isFalse());
    }

    private void assertGeneratedUuid(Class<?> entityType) throws Exception {
        Field id = entityType.getDeclaredField("id");
        assertThat(id.getAnnotation(GeneratedValue.class)).isNotNull();
        assertThat(id.getAnnotation(UuidGenerator.class)).isNotNull();
    }

    private void assertColumns(Class<?> entityType, List<String> expectedNames) {
        assertThat(entityType.getDeclaredFields())
            .filteredOn(field -> field.isAnnotationPresent(Column.class))
            .extracting(field -> {
                String explicitName = field.getAnnotation(Column.class).name();
                return explicitName.isBlank() ? field.getName() : explicitName;
            })
            .containsExactlyInAnyOrderElementsOf(expectedNames);
    }

    private void assertColumn(Class<?> type, String fieldName, String columnName) {
        try {
            Field field = type.getDeclaredField(fieldName);
            assertThat(field.getAnnotation(Column.class)).isNotNull();
            assertThat(field.getAnnotation(Column.class).name()).isEqualTo(columnName);
        } catch (NoSuchFieldException exception) {
            throw new AssertionError("Missing field " + type.getSimpleName() + "." + fieldName, exception);
        }
    }
}
