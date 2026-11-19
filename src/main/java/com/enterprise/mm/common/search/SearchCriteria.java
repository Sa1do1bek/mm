package com.enterprise.mm.common.search;

import com.enterprise.mm.enums.MovementType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface SearchCriteria {
    static SearchCriteria empty() {
        return EmptySearchCriteria.INSTANCE;
    }

    static SearchCriteria of(SearchCriteria criteria) {
        return criteria == null ? empty() : criteria;
    }

    static SearchCriteria withWarehouseId(SearchCriteria criteria, UUID warehouseId) {
        SearchCriteria filter = of(criteria);
        return warehouseId == null ? filter : new WarehouseScopedSearchCriteria(filter, warehouseId, List.of(warehouseId));
    }

    static SearchCriteria withWarehouseIds(SearchCriteria criteria, Collection<UUID> warehouseIds) {
        return withWarehouseScope(criteria, null, warehouseIds);
    }

    static SearchCriteria withWarehouseScope(
        SearchCriteria criteria,
        UUID warehouseId,
        Collection<UUID> warehouseIds
    ) {
        SearchCriteria filter = of(criteria);
        if (warehouseIds == null || warehouseIds.isEmpty()) {
            return warehouseId == null ? filter : withWarehouseId(filter, warehouseId);
        }
        return new WarehouseScopedSearchCriteria(
            filter,
            warehouseId,
            warehouseIds.stream().filter(java.util.Objects::nonNull).distinct().toList()
        );
    }

    default String search() {
        return null;
    }

    default List<String> status() {
        return null;
    }

    default UUID warehouseId() {
        return null;
    }

    default UUID supplierId() {
        return null;
    }

    default UUID contractId() {
        return null;
    }

    default UUID purchaseOrderId() {
        return null;
    }

    default UUID inventoryId() {
        return null;
    }

    default List<UUID> materialIds() {
        return null;
    }

    default LocalDate dateFrom() {
        return null;
    }

    default LocalDate dateTo() {
        return null;
    }

    default boolean includeDeleted() {
        return false;
    }

    default UUID referenceId() {
        return null;
    }

    default String referenceType() {
        return null;
    }

    default String referenceEntityType() {
        return null;
    }

    default String externalSystem() {
        return null;
    }

    default String accountingArea() {
        return null;
    }

    default MovementType movementType() {
        return null;
    }

    default List<MovementType> movementTypes() {
        return null;
    }

    default String materialCode() {
        return null;
    }

    default String materialName() {
        return null;
    }

    default UUID materialId() {
        return null;
    }

    default List<UUID> warehouseIds() {
        return null;
    }

    default LocalDate movementDate() {
        return null;
    }

    default boolean hasWarehouseIds() {
        return !isNullOrEmpty(warehouseIds());
    }

    default boolean hasMovementTypes() {
        return !isNullOrEmpty(movementTypes());
    }

    default String normalizedSearch() {
        String normalized = normalizeText(search());
        return normalized == null ? "" : normalized;
    }

    default LocalDateTime fromDateTime() {
        return dateFrom() == null ? null : dateFrom().atStartOfDay();
    }

    default LocalDateTime toDateTime() {
        return dateTo() == null ? null : LocalDateTime.of(dateTo(), LocalTime.MAX);
    }

    default boolean hasStatus() {
        return !isNullOrEmpty(status());
    }

    default List<String> statusOrNull() {
        return hasStatus() ? status() : null;
    }

    default boolean hasMaterialIds() {
        return !isNullOrEmpty(materialIds());
    }

    default List<UUID> materialIdsOrNull() {
        return hasMaterialIds() ? materialIds() : null;
    }

    default String normalizedReferenceEntityType() {
        return normalizeTokenValue(referenceEntityType());
    }

    default String normalizedReferenceType() {
        return normalizeTokenValue(referenceType());
    }

    default String normalizedExternalSystem() {
        return normalizeTokenValue(externalSystem());
    }

    default String normalizedAccountingArea() {
        return normalizeTokenValue(accountingArea());
    }

    static String normalizedToken(String value) {
        return normalizeTokenValue(value);
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeTokenValue(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean isNullOrEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    enum EmptySearchCriteria implements SearchCriteria {
        INSTANCE;

        @Override
        public String search() {
            return null;
        }

        @Override
        public List<String> status() {
            return null;
        }

        @Override
        public LocalDate dateFrom() {
            return null;
        }

        @Override
        public LocalDate dateTo() {
            return null;
        }

        @Override
        public boolean includeDeleted() {
            return false;
        }
    }

    record WarehouseScopedSearchCriteria(
        SearchCriteria delegate,
        UUID warehouseId,
        List<UUID> scopedWarehouseIds
    ) implements SearchCriteria {
        @Override
        public String search() {
            return delegate.search();
        }

        @Override
        public List<String> status() {
            return delegate.status();
        }

        @Override
        public UUID supplierId() {
            return delegate.supplierId();
        }

        @Override
        public UUID contractId() {
            return delegate.contractId();
        }

        @Override
        public UUID purchaseOrderId() {
            return delegate.purchaseOrderId();
        }

        @Override
        public UUID inventoryId() {
            return delegate.inventoryId();
        }

        @Override
        public List<UUID> materialIds() {
            return delegate.materialIds();
        }

        @Override
        public LocalDate dateFrom() {
            return delegate.dateFrom();
        }

        @Override
        public LocalDate dateTo() {
            return delegate.dateTo();
        }

        @Override
        public boolean includeDeleted() {
            return delegate.includeDeleted();
        }

        @Override
        public UUID referenceId() {
            return delegate.referenceId();
        }

        @Override
        public String referenceType() {
            return delegate.referenceType();
        }

        @Override
        public String referenceEntityType() {
            return delegate.referenceEntityType();
        }

        @Override
        public String externalSystem() {
            return delegate.externalSystem();
        }

        @Override
        public String accountingArea() {
            return delegate.accountingArea();
        }

        @Override
        public MovementType movementType() {
            return delegate.movementType();
        }

        @Override
        public List<MovementType> movementTypes() {
            return delegate.movementTypes();
        }

        @Override
        public String materialCode() {
            return delegate.materialCode();
        }

        @Override
        public String materialName() {
            return delegate.materialName();
        }

        @Override
        public UUID materialId() {
            return delegate.materialId();
        }

        @Override
        public List<UUID> warehouseIds() {
            return scopedWarehouseIds;
        }

        @Override
        public LocalDate movementDate() {
            return delegate.movementDate();
        }
    }
}
