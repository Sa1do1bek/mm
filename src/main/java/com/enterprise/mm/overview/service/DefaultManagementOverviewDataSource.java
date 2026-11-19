package com.enterprise.mm.overview.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.ConcentrationCell;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.DecisionRow;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.LeadTimeBucket;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.MonthTrend;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SourceHealth;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SourceStatus;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SupplierExposure;
import com.enterprise.mm.overview.repository.ManagementOverviewQueryRepository;
import com.enterprise.mm.payload.ManagementOpportunityResponse;
import com.enterprise.mm.payload.ManagementOpportunitySearchRequest;
import com.enterprise.mm.payload.ReportDtos.InventoryValuationReportDto;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.ManagementOpportunityService;
import com.enterprise.mm.service.ReportService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultManagementOverviewDataSource implements ManagementOverviewDataSource {
    private static final Logger log = LoggerFactory.getLogger(DefaultManagementOverviewDataSource.class);
    private static final List<String> COMPLETED = List.of("RECEIVED", "CLOSED");

    private final ManagementOverviewQueryRepository queryRepository;
    private final InventoryAccountingSettingsRepository accountingSettingsRepository;
    private final SupplierRepository supplierRepository;
    private final ReportService reportService;
    private final ManagementOpportunityService opportunityService;
    private final SupplierExposureCalculator supplierExposureCalculator;

    @Override
    public ManagementFacts load(LocalDate from, LocalDate to, String requestedCurrency) {
        Instant generatedAt = Instant.now();
        List<SourceHealth> health = new ArrayList<>();
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        InventoryAccountingSettings settings = accountingSettingsRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId)
            .orElse(null);
        String baseCurrency = settings == null || blank(settings.getBaseCurrencyCode())
            ? "UZS" : settings.getBaseCurrencyCode().trim().toUpperCase(Locale.ROOT);
        if (settings == null) {
            health.add(new SourceHealth(
                "accountingSettings",
                "Учётная политика",
                SourceStatus.DEGRADED,
                "Базовая валюта не настроена явно; используется UZS.",
                generatedAt,
                "/app/inventory-accounting-settings"
            ));
        } else {
            health.add(healthy("accountingSettings", "Учётная политика", generatedAt,
                "/app/inventory-accounting-settings"));
        }
        String currency = blank(requestedCurrency)
            ? baseCurrency : requestedCurrency.trim().toUpperCase(Locale.ROOT);

        List<PurchaseOrder> orders = safe(
            "procurement",
            "Заказы поставщикам",
            "/app/purchase-orders",
            health,
            generatedAt,
            () -> queryRepository.purchaseOrders(tenantId, companyId, from, to, currency)
        );
        OpenOrderExposureFacts openOrderExposure = safe(
            "supplierExposure",
            "Текущая экспозиция поставщиков",
            "/app/suppliers",
            health,
            generatedAt,
            () -> {
                List<PurchaseOrder> openOrders = queryRepository.openPurchaseOrders(
                    tenantId, companyId, currency);
                Map<UUID, BigDecimal> remainingValueByOrder = queryRepository.remainingValueByOrder(
                    tenantId,
                    companyId,
                    openOrders.stream().map(PurchaseOrder::getId).toList()
                );
                return new OpenOrderExposureFacts(openOrders, remainingValueByOrder);
            }
        );

        InventoryFacts inventory = null;
        if (currency.equals(baseCurrency)) {
            inventory = safe(
                "inventory",
                "Оценка запасов",
                "/app/valuation",
                health,
                generatedAt,
                () -> inventoryFacts(reportService.inventoryValuation(null, null), baseCurrency)
            );
        } else {
            health.add(new SourceHealth(
                "inventory",
                "Оценка запасов",
                SourceStatus.UNAVAILABLE,
                "Стоимость запасов ведётся в " + baseCurrency
                    + "; подтверждённая конвертация в " + currency + " не выполнялась.",
                null,
                "/app/valuation"
            ));
        }

        Map<UUID, Supplier> suppliers = safe(
            "suppliers",
            "Поставщики",
            "/app/suppliers",
            health,
            generatedAt,
            () -> supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(tenantId, companyId)
                .stream().collect(Collectors.toMap(Supplier::getId, item -> item))
        );

        List<DecisionRow> decisions = safe(
            "opportunities",
            "Управленческие возможности",
            "/app/management-opportunities",
            health,
            generatedAt,
            () -> opportunityDecisions(from, to, currency)
        );

        health.add(new SourceHealth(
            "demandFulfilment",
            "Исполнение спроса",
            SourceStatus.DEGRADED,
            "Сквозная связь строк спроса с фактической выдачей и требуемой датой пока неполна; KPI не рассчитывается.",
            generatedAt,
            "/app/stock-balances"
        ));
        health.add(new SourceHealth(
            "orderStatusTrend",
            "История статусов заказов",
            SourceStatus.DEGRADED,
            "Месячный ряд группирует заказы по дате создания и показывает их текущий статус; исторический статус на конец месяца не реконструируется.",
            generatedAt,
            "/app/purchase-orders"
        ));

        OrderFacts orderFacts = orders == null ? null : orderFacts(orders, from, to, currency);
        List<SupplierExposure> supplierExposure = openOrderExposure == null || suppliers == null
            ? List.of()
            : supplierExposureCalculator.calculate(
                openOrderExposure.orders,
                suppliers,
                openOrderExposure.remainingValueByOrder,
                currency,
                LocalDate.now()
            );

        return new ManagementFacts(
            currency,
            null,
            null,
            inventory == null ? null : inventory.gross,
            inventory == null ? null : inventory.impairment,
            inventory == null ? null : inventory.frozen,
            orderFacts == null ? null : orderFacts.deliveryCount,
            orderFacts == null ? null : orderFacts.overdueCount,
            orderFacts == null ? null : orderFacts.orderedCount,
            orderFacts == null ? null : orderFacts.completedCount,
            orderFacts == null ? List.of() : orderFacts.trends,
            inventory == null ? List.of() : inventory.concentration,
            supplierExposure,
            orders == null ? List.of() : leadTime(orders),
            decisions == null ? List.of() : decisions,
            health
        );
    }

    private InventoryFacts inventoryFacts(List<InventoryValuationReportDto> rows, String currency) {
        BigDecimal gross = rows.stream().map(InventoryValuationReportDto::totalValue)
            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal impairment = rows.stream().map(InventoryValuationReportDto::impairmentReserve)
            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal frozen = rows.stream().map(InventoryValuationReportDto::frozenMoney)
            .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<ConcentrationKey, BigDecimal> grouped = new LinkedHashMap<>();
        for (InventoryValuationReportDto row : rows) {
            ConcentrationKey key = new ConcentrationKey(
                row.warehouseId(),
                label(row.warehouseCode(), row.warehouseName(), "Без склада"),
                row.materialGroupId(),
                label(row.materialGroupCode(), row.materialGroupName(), "Без группы")
            );
            grouped.merge(key, nvl(row.totalValue()), BigDecimal::add);
        }
        List<ConcentrationCell> concentration = grouped.entrySet().stream()
            .map(entry -> new ConcentrationCell(
                entry.getKey().warehouseId,
                entry.getKey().warehouseLabel,
                entry.getKey().materialGroupId,
                entry.getKey().materialGroupLabel,
                entry.getValue(),
                currency,
                gross.signum() == 0 ? BigDecimal.ZERO : entry.getValue()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(gross, 2, RoundingMode.HALF_UP),
                "/app/stock-balances?warehouseId=" + value(entry.getKey().warehouseId)
                    + "&materialGroupId=" + value(entry.getKey().materialGroupId)
            ))
            .sorted(Comparator.comparing(ConcentrationCell::value).reversed())
            .limit(24)
            .toList();
        return new InventoryFacts(gross, impairment, frozen, concentration);
    }

    private OrderFacts orderFacts(List<PurchaseOrder> orders, LocalDate from, LocalDate to, String currency) {
        LocalDate today = LocalDate.now();
        long deliveryCount = orders.stream()
            .filter(ManagementOrderCohort::isDatedOpen)
            .count();
        long overdueCount = orders.stream()
            .filter(item -> ManagementOrderCohort.isOverdueOpen(item, today))
            .count();
        long completed = orders.stream().filter(item -> COMPLETED.contains(item.getStatus())).count();
        Map<YearMonth, List<PurchaseOrder>> byMonth = orders.stream()
            .filter(item -> item.getOrderDate() != null)
            .collect(Collectors.groupingBy(item -> YearMonth.from(item.getOrderDate())));
        List<MonthTrend> trends = new ArrayList<>();
        YearMonth month = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        while (!month.isAfter(last)) {
            List<PurchaseOrder> monthly = byMonth.getOrDefault(month, List.of());
            trends.add(new MonthTrend(
                month,
                monthly.stream().map(PurchaseOrder::getTotalAmount).map(this::nvl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add),
                monthly.stream().filter(item -> COMPLETED.contains(item.getStatus()))
                    .map(PurchaseOrder::getTotalAmount).map(this::nvl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add),
                monthly.stream().filter(item -> ManagementOrderCohort.isOverdueOpen(item, today)).count(),
                null,
                null,
                null
            ));
            month = month.plusMonths(1);
        }
        return new OrderFacts(deliveryCount, overdueCount, (long) orders.size(), completed, trends);
    }

    private List<LeadTimeBucket> leadTime(List<PurchaseOrder> orders) {
        long days07 = 0;
        long days814 = 0;
        long days1530 = 0;
        long days3160 = 0;
        long days61 = 0;
        for (PurchaseOrder order : orders) {
            if (order.getOrderDate() == null || order.getExpectedDeliveryDate() == null) {
                continue;
            }
            long days = Math.max(0, ChronoUnit.DAYS.between(order.getOrderDate(), order.getExpectedDeliveryDate()));
            if (days <= 7) {
                days07++;
            } else if (days <= 14) {
                days814++;
            } else if (days <= 30) {
                days1530++;
            } else if (days <= 60) {
                days3160++;
            } else {
                days61++;
            }
        }
        return List.of(
            new LeadTimeBucket("DAYS_0_7", "0–7 дней", days07),
            new LeadTimeBucket("DAYS_8_14", "8–14 дней", days814),
            new LeadTimeBucket("DAYS_15_30", "15–30 дней", days1530),
            new LeadTimeBucket("DAYS_31_60", "31–60 дней", days3160),
            new LeadTimeBucket("DAYS_61_PLUS", "61+ дней", days61)
        );
    }

    private List<DecisionRow> opportunityDecisions(LocalDate from, LocalDate to, String currency) {
        var request = new ManagementOpportunitySearchRequest(
            null, null, null, null, null, null, null, null, null, null, from, to, false);
        return opportunityService.search(
                request,
                PageRequest.of(0, 12, Sort.by("createdAt").descending()))
            .stream()
            .filter(item -> !"ARCHIVED".equalsIgnoreCase(item.getStatus()))
            .map(item -> decision(item, currency))
            .toList();
    }

    private DecisionRow decision(ManagementOpportunityResponse item, String currency) {
        String id = item.getId() == null ? value(item.getCode()) : item.getId().toString();
        boolean comparable = currency.equalsIgnoreCase(value(item.getNormalizedCurrencyCode()));
        String status = value(item.getStatus());
        String nextAction = switch (status) {
            case "READY_FOR_REVIEW" -> "Принять управленческое решение";
            case "APPROVED" -> "Проверить реализацию эффекта";
            case "DRAFT" -> "Завершить доказательную базу";
            default -> "Проверить кейс";
        };
        return new DecisionRow(
            "opportunity:" + id,
            label(item.getCode(), item.getName(), String.valueOf(item.getOpportunityType())),
            "READY_FOR_REVIEW".equals(status) ? "CRITICAL" : "WARNING",
            comparable ? item.getNormalizedAmount() : null,
            comparable ? currency : null,
            label(item.getAssignedRole(), null, "MM_MANAGER"),
            null,
            nextAction,
            "/app/management-opportunities?caseId=" + id,
            status
        );
    }

    private SourceHealth healthy(String key, String label, Instant updatedAt, String url) {
        return new SourceHealth(key, label, SourceStatus.HEALTHY, null, updatedAt, url);
    }

    private <T> T safe(
        String key,
        String label,
        String url,
        List<SourceHealth> health,
        Instant generatedAt,
        java.util.function.Supplier<T> loader
    ) {
        try {
            T result = loader.get();
            health.add(healthy(key, label, generatedAt, url));
            return result;
        } catch (RuntimeException exception) {
            log.warn("Management overview source {} is unavailable: {}", key, exception.getMessage());
            health.add(new SourceHealth(
                key, label, SourceStatus.UNAVAILABLE,
                "Источник «" + label + "» временно недоступен.", null, url));
            return null;
        }
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String label(String first, String second, String fallback) {
        if (!blank(first) && !blank(second)) {
            return first + " · " + second;
        }
        if (!blank(first)) {
            return first;
        }
        if (!blank(second)) {
            return second;
        }
        return fallback;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record ConcentrationKey(
        UUID warehouseId,
        String warehouseLabel,
        UUID materialGroupId,
        String materialGroupLabel
    ) {
    }

    private record InventoryFacts(
        BigDecimal gross,
        BigDecimal impairment,
        BigDecimal frozen,
        List<ConcentrationCell> concentration
    ) {
    }

    private record OrderFacts(
        long deliveryCount,
        long overdueCount,
        long orderedCount,
        long completedCount,
        List<MonthTrend> trends
    ) {
    }

    private record OpenOrderExposureFacts(
        List<PurchaseOrder> orders,
        Map<UUID, BigDecimal> remainingValueByOrder
    ) {
    }
}
