package com.enterprise.mm.warehouse.movements;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.StockMovementSearchRequest;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;

/** Public sort names are deliberately independent of SQL/JPQL expressions. */
public final class MovementQuerySupport {
    private MovementQuerySupport() {}
    public static final Map<String,String> SQL_SORTS = Map.of(
        "movementDate", "movement_date", "createdAt", "created_at", "code", "code",
        "movementType", "movement_type", "materialCode", "material_code",
        "materialName", "material_name", "quantity", "quantity", "status", "status", "id", "id");

    public static Sort sort(List<String> values) {
        List<Sort.Order> orders = new ArrayList<>();
        if (values == null || values.isEmpty()) orders.add(Sort.Order.desc("movementDate"));
        else for (String value : values) {
            String[] parts = value.split(",", -1);
            if (parts.length > 2 || !SQL_SORTS.containsKey(parts[0]))
                throw new BadRequestException("Unsupported movement sort: " + value);
            String direction = parts.length == 1 ? "asc" : parts[1];
            if (!direction.equalsIgnoreCase("asc") && !direction.equalsIgnoreCase("desc"))
                throw new BadRequestException("Sort direction must be asc or desc");
            orders.add(new Sort.Order(Sort.Direction.fromString(direction), parts[0]));
        }
        if (orders.stream().noneMatch(o -> o.getProperty().equals("id"))) orders.add(Sort.Order.asc("id"));
        return Sort.by(orders);
    }

    public static Sort jpaSort(Sort sort) {
        Sort result = Sort.unsorted();
        for (Sort.Order order : sort) {
            String expression = switch (order.getProperty()) {
                case "movementDate" -> "COALESCE(e.movementDate, CAST(e.createdAt AS date))";
                case "materialName" -> "multilingual_name_value(material.name, '" + MovementExportText.language(null) + "')";
                case "materialCode" -> "material.code";
                default -> "e." + order.getProperty();
            };
            if (!SQL_SORTS.containsKey(order.getProperty())) throw new BadRequestException("Unsupported movement sort");
            result = result.and(JpaSort.unsafe(order.getDirection(), expression));
        }
        return result;
    }

    public static void validate(StockMovementSearchRequest filter) {
        if (filter.dateFrom() != null && filter.dateTo() != null && filter.dateFrom().isAfter(filter.dateTo()))
            throw new BadRequestException("dateFrom must be before or equal to dateTo");
    }
}
