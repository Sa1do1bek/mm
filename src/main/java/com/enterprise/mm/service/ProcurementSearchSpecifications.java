package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.search.SearchCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

final class ProcurementSearchSpecifications {
    private ProcurementSearchSpecifications() {
    }

    static <T extends BusinessEntity> Specification<T> build(
        Class<T> entityType,
        String tenantId,
        UUID companyId,
        SearchCriteria request,
        Record entityFilter
    ) {
        SearchCriteria criteria = SearchCriteria.of(request);
        Set<String> fields = entityFields(entityType);
        boolean includeDeleted = criteria.includeDeleted() || includeDeleted(entityFilter);

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (companyId != null) {
                predicates.add(cb.equal(root.get("companyId"), companyId));
            }
            if (!includeDeleted) {
                predicates.add(cb.isFalse(root.get("isDeleted")));
            }

            addCriteriaPredicates(root, cb, predicates, criteria, fields);
            addRecordPredicates(root, cb, predicates, entityFilter, fields);
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static <T extends BusinessEntity> void addCriteriaPredicates(
        Root<T> root,
        CriteriaBuilder cb,
        List<Predicate> predicates,
        SearchCriteria request,
        Set<String> fields
    ) {
        String search = blankToNull(request.normalizedSearch());
        if (search != null) {
            String like = contains(search);
            predicates.add(cb.or(
                cb.like(cb.lower(root.get("code")), like),
                cb.like(cb.lower(root.get("name")), like)
            ));
        }
        if (request.fromDateTime() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), request.fromDateTime()));
        }
        if (request.toDateTime() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), request.toDateTime()));
        }
        if (request.hasStatus()) {
            predicates.add(root.get("status").in(request.status()));
        }
        if (request.hasWarehouseIds() && fields.contains("warehouseId")) {
            predicates.add(root.get("warehouseId").in(request.warehouseIds()));
        } else {
            addExactIfField(root, cb, predicates, fields, "warehouseId", request.warehouseId());
        }
        addExactIfField(root, cb, predicates, fields, "supplierId", request.supplierId());
        addExactIfField(root, cb, predicates, fields, "contractId", request.contractId());
        addExactIfField(root, cb, predicates, fields, "purchaseOrderId", request.purchaseOrderId());
        if (request.hasMaterialIds() && fields.contains("materialId")) {
            predicates.add(root.get("materialId").in(request.materialIds()));
        }
    }

    private static <T extends BusinessEntity> void addRecordPredicates(
        Root<T> root,
        CriteriaBuilder cb,
        List<Predicate> predicates,
        Record filter,
        Set<String> fields
    ) {
        if (filter == null) {
            return;
        }
        for (RecordComponent component : filter.getClass().getRecordComponents()) {
            String componentName = component.getName();
            if ("includeDeleted".equals(componentName)) {
                continue;
            }
            Object value = value(filter, component);
            if (isEmpty(value)) {
                continue;
            }

            boolean handled = false;
            if (componentName.endsWith("From")) {
                String fieldName = componentName.substring(0, componentName.length() - "From".length());
                if (fields.contains(fieldName)) {
                    addLowerBound(root, cb, predicates, fieldName, value);
                    handled = true;
                }
            } else if (componentName.endsWith("To")) {
                String fieldName = componentName.substring(0, componentName.length() - "To".length());
                if (fields.contains(fieldName)) {
                    addUpperBound(root, cb, predicates, fieldName, value);
                    handled = true;
                }
            } else if (componentName.endsWith("Min")) {
                String fieldName = componentName.substring(0, componentName.length() - "Min".length());
                if (fields.contains(fieldName)) {
                    addLowerBound(root, cb, predicates, fieldName, value);
                    handled = true;
                }
            } else if (componentName.endsWith("Max")) {
                String fieldName = componentName.substring(0, componentName.length() - "Max".length());
                if (fields.contains(fieldName)) {
                    addUpperBound(root, cb, predicates, fieldName, value);
                    handled = true;
                }
            }
            if (!handled && fields.contains(componentName)) {
                addFieldPredicate(root, cb, predicates, componentName, value);
            }
        }
    }

    private static <T extends BusinessEntity> void addFieldPredicate(
        Root<T> root,
        CriteriaBuilder cb,
        List<Predicate> predicates,
        String fieldName,
        Object value
    ) {
        if (value instanceof Collection<?> values) {
            predicates.add(root.get(fieldName).in(values));
        } else if (value instanceof String text) {
            predicates.add(cb.like(cb.lower(root.get(fieldName)), contains(text)));
        } else {
            predicates.add(cb.equal(root.get(fieldName), value));
        }
    }

    private static <T extends BusinessEntity> void addExactIfField(
        Root<T> root,
        CriteriaBuilder cb,
        List<Predicate> predicates,
        Set<String> fields,
        String fieldName,
        Object value
    ) {
        if (value != null && fields.contains(fieldName)) {
            predicates.add(cb.equal(root.get(fieldName), value));
        }
    }

    private static <T extends BusinessEntity> void addLowerBound(
        Root<T> root,
        CriteriaBuilder cb,
        List<Predicate> predicates,
        String fieldName,
        Object value
    ) {
        if (value instanceof LocalDate date) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(fieldName), date));
        } else if (value instanceof LocalDateTime dateTime) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(fieldName), dateTime));
        } else if (value instanceof BigDecimal decimal) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(fieldName), decimal));
        } else if (value instanceof Integer integer) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(fieldName), integer));
        }
    }

    private static <T extends BusinessEntity> void addUpperBound(
        Root<T> root,
        CriteriaBuilder cb,
        List<Predicate> predicates,
        String fieldName,
        Object value
    ) {
        if (value instanceof LocalDate date) {
            predicates.add(cb.lessThanOrEqualTo(root.get(fieldName), date));
        } else if (value instanceof LocalDateTime dateTime) {
            predicates.add(cb.lessThanOrEqualTo(root.get(fieldName), dateTime));
        } else if (value instanceof BigDecimal decimal) {
            predicates.add(cb.lessThanOrEqualTo(root.get(fieldName), decimal));
        } else if (value instanceof Integer integer) {
            predicates.add(cb.lessThanOrEqualTo(root.get(fieldName), integer));
        }
    }

    private static Set<String> entityFields(Class<?> entityType) {
        Set<String> fields = new HashSet<>();
        Class<?> current = entityType;
        while (current != null && BusinessEntity.class.isAssignableFrom(current)) {
            for (var field : current.getDeclaredFields()) {
                fields.add(field.getName());
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static boolean includeDeleted(Record filter) {
        if (filter == null) {
            return false;
        }
        try {
            Object value = filter.getClass().getMethod("includeDeleted").invoke(filter);
            return Boolean.TRUE.equals(value);
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    private static Object value(Record record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot read search filter component " + component.getName(), ex);
        }
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.trim().isEmpty();
        }
        if (value instanceof Collection<?> values) {
            return values.isEmpty();
        }
        return false;
    }

    private static String contains(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
