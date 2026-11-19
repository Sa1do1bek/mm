package com.enterprise.mm.overview.service;

import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SupplierExposure;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SupplierExposureCalculator {
    public List<SupplierExposure> calculate(
        List<PurchaseOrder> orders,
        Map<UUID, Supplier> suppliers,
        Map<UUID, BigDecimal> remainingValueByOrder,
        String currency,
        LocalDate today
    ) {
        return orders.stream()
            .filter(ManagementOrderCohort::isOpen)
            .collect(Collectors.groupingBy(PurchaseOrder::getSupplierId))
            .entrySet().stream()
            .map(entry -> exposure(
                entry.getKey(), entry.getValue(), suppliers, remainingValueByOrder, currency, today))
            .sorted(Comparator.comparing(SupplierExposure::openOutstandingValue).reversed())
            .limit(20)
            .toList();
    }

    private SupplierExposure exposure(
        UUID supplierId,
        List<PurchaseOrder> openOrders,
        Map<UUID, Supplier> suppliers,
        Map<UUID, BigDecimal> remainingValueByOrder,
        String currency,
        LocalDate today
    ) {
        Supplier supplier = suppliers.get(supplierId);
        long datedOpenOrders = openOrders.stream()
            .filter(order -> order.getExpectedDeliveryDate() != null)
            .count();
        long overdueOpenOrders = openOrders.stream()
            .filter(order -> order.getExpectedDeliveryDate() != null)
            .filter(order -> order.getExpectedDeliveryDate().isBefore(today))
            .count();
        BigDecimal scheduleHealth = datedOpenOrders == 0 ? null
            : BigDecimal.valueOf(datedOpenOrders - overdueOpenOrders)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(datedOpenOrders), 2, RoundingMode.HALF_UP);
        BigDecimal outstanding = openOrders.stream()
            .map(PurchaseOrder::getId)
            .map(id -> remainingValueByOrder.getOrDefault(id, BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SupplierExposure(
            supplierId,
            supplier == null ? null : supplier.getCode(),
            supplier == null ? "Недоступный поставщик" : label(
                supplier.getShortName(), supplier.getName(), supplier.getCode()),
            supplier == null || supplier.getRating() == null ? null : supplier.getRating(),
            outstanding,
            currency,
            scheduleHealth,
            openOrders.size(),
            datedOpenOrders,
            supplier != null && "BLOCKED".equalsIgnoreCase(supplier.getStatus()),
            "/app/suppliers?supplierId=" + supplierId
        );
    }

    private String label(String first, String second, String fallback) {
        if (first != null && !first.isBlank() && second != null && !second.isBlank()) {
            return first + " · " + second;
        }
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }
}
