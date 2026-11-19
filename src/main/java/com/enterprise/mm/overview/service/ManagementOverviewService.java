package com.enterprise.mm.overview.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.NonMovingStockCaseStatus;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.Health;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.ConcentrationCell;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.DecisionRow;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.ManagementOverview;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.Metadata;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.MonthTrend;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.DatasetMetadata;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.Outcome;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SourceHealth;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SourceStatus;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.SupplierExposure;
import com.enterprise.mm.overview.payload.ManagementOverviewDtos.WorkingCapitalStep;
import com.enterprise.mm.overview.service.ManagementOverviewDataSource.ManagementFacts;
import com.enterprise.mm.security.CompanyScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ManagementOverviewService {
    private final ManagementOverviewDataSource dataSource;
    private final Clock clock;

    @Autowired
    public ManagementOverviewService(ManagementOverviewDataSource dataSource) {
        this(dataSource, Clock.systemDefaultZone());
    }

    public ManagementOverviewService(ManagementOverviewDataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    public ManagementOverview overview(LocalDate requestedFrom, LocalDate requestedTo, String requestedCurrency) {
        LocalDate today = LocalDate.now(clock);
        LocalDate to = requestedTo == null ? today : requestedTo;
        LocalDate from = requestedFrom == null ? to.minusMonths(11).withDayOfMonth(1) : requestedFrom;
        validate(from, to);
        String currency = normalizeCurrency(requestedCurrency);
        ManagementFacts facts = dataSource.load(from, to, currency);
        String effectiveCurrency = normalizeCurrency(facts.currencyCode());
        if (effectiveCurrency == null) {
            effectiveCurrency = currency;
        }
        Instant generatedAt = Instant.now(clock);

        List<String> messages = facts.sourceHealth().stream()
            .filter(item -> item.status() != SourceStatus.HEALTHY)
            .map(SourceHealth::message)
            .filter(message -> message != null && !message.isBlank())
            .distinct()
            .toList();
        Health status = facts.sourceHealth().stream().anyMatch(item -> item.status() != SourceStatus.HEALTHY)
            ? Health.PARTIAL : Health.READY;

        return new ManagementOverview(
            new Metadata(
                generatedAt,
                from,
                to,
                CompanyContext.currentCompany(),
                "COMPANY",
                effectiveCurrency,
                status,
                messages
            ),
            datasets(from, to, generatedAt),
            outcomes(facts, effectiveCurrency, from, to),
            scopedTrends(facts.trends(), effectiveCurrency, from, to),
            workingCapital(facts, effectiveCurrency),
            scopedConcentration(facts.concentration()),
            scopedSupplierExposure(facts.supplierExposure()),
            facts.leadTime(),
            scopedDecisions(facts.decisions()),
            scopedSourceHealth(facts.sourceHealth())
        );
    }

    private List<DatasetMetadata> datasets(LocalDate from, LocalDate to, Instant generatedAt) {
        return List.of(
            new DatasetMetadata(
                "procurement",
                "SELECTED_PERIOD",
                from,
                to,
                null,
                "Заказы, сроки и завершение сгруппированы по дате заказа внутри выбранного периода."
            ),
            new DatasetMetadata(
                "inventory",
                "CURRENT_SNAPSHOT",
                null,
                null,
                generatedAt,
                "Стоимость, резерв и концентрация запасов отражают текущий снимок и не пересчитываются на выбранный период закупок."
            ),
            new DatasetMetadata(
                "supplierExposure",
                "CURRENT_SNAPSHOT",
                null,
                null,
                generatedAt,
                "Экспозиция поставщиков отражает текущую неполученную стоимость всех открытых заказов в выбранной валюте."
            )
        );
    }

    private List<Outcome> outcomes(
        ManagementFacts facts,
        String currency,
        LocalDate from,
        LocalDate to
    ) {
        return List.of(
            percentOutcome(
                "on_time_fulfilled_demand",
                "Спрос исполнен в срок",
                facts.fulfilledOnTimeCount(),
                facts.fulfilledDemandCount(),
                "Доля строк спроса, полностью обеспеченных и выданных не позднее требуемой даты.",
                "/app/stock-balances"
            ),
            moneyOutcome(
                "inventory_at_risk",
                "Запасы под риском",
                facts.frozenExposure(),
                currency,
                "Балансовая стоимость активных позиций неликвидных запасов. Резерв обесценения показывается отдельно и не прибавляется повторно.",
                openNonMovingCasesUrl()
            ),
            percentOutcome(
                "procurement_sla",
                "Соблюдение сроков поставки",
                subtract(facts.deliveryScheduleCount(), facts.overdueDeliveryCount()),
                facts.deliveryScheduleCount(),
                "Доля открытых поставок выбранного периода, не просроченных относительно ожидаемой даты.",
                purchaseOrderUrl(from, to, currency, ManagementOrderCohort.openStatuses(), true)
            ),
            percentOutcome(
                "order_completion",
                "Заказы завершены",
                facts.completedOrderCount(),
                facts.orderedCount(),
                "Доля заказов выбранного периода в статусе RECEIVED или CLOSED; показатель основан на количестве заказов, не на сумме.",
                purchaseOrderUrl(
                    from,
                    to,
                    currency,
                    java.util.Arrays.stream(PurchaseOrderStatus.values())
                        .filter(status -> status != PurchaseOrderStatus.CANCELLED)
                        .map(Enum::name)
                        .toList(),
                    false
                )
            )
        );
    }

    private String purchaseOrderUrl(
        LocalDate from,
        LocalDate to,
        String currency,
        List<String> statuses,
        boolean requireExpectedDeliveryDate
    ) {
        return purchaseOrderUrl(from, to, currency, statuses, requireExpectedDeliveryDate, null);
    }

    private String purchaseOrderUrl(
        LocalDate from,
        LocalDate to,
        String currency,
        List<String> statuses,
        boolean requireExpectedDeliveryDate,
        LocalDate expectedDeliveryDateTo
    ) {
        StringBuilder url = new StringBuilder("/app/purchase-orders?tab=registry")
            .append("&orderDateFrom=").append(from)
            .append("&orderDateTo=").append(to);
        if (requireExpectedDeliveryDate) {
            // The SLA denominator contains only dated open orders. This supported lower-bound
            // filter excludes null dates while retaining every persistable business date.
            url.append("&expectedDeliveryDateFrom=0001-01-01");
        }
        if (expectedDeliveryDateTo != null) {
            url.append("&expectedDeliveryDateTo=").append(expectedDeliveryDateTo);
        }
        if (currency != null) {
            url.append("&currencyCode=").append(currency);
        }
        statuses.forEach(status -> url.append("&status=").append(status));
        return CompanyScope.currentCompanyDrilldown(url.toString());
    }

    private List<WorkingCapitalStep> workingCapital(ManagementFacts facts, String currency) {
        if (facts.grossInventoryValue() == null || facts.impairmentReserve() == null
            || facts.frozenExposure() == null) {
            return List.of();
        }
        BigDecimal net = facts.grossInventoryValue().subtract(facts.impairmentReserve());
        return List.of(
            capital("GROSS_INVENTORY", "Валовая стоимость запасов", facts.grossInventoryValue(), currency, "START", "/app/valuation"),
            capital("IMPAIRMENT_RESERVE", "Резерв обесценения", facts.impairmentReserve().negate(), currency, "DECREASE", "/app/valuation"),
            capital("NET_INVENTORY", "Чистая стоимость запасов", net, currency, "TOTAL", "/app/valuation"),
            capital("FROZEN_EXPOSURE", "Заморожено в неликвидах", facts.frozenExposure(), currency, "REFERENCE", openNonMovingCasesUrl())
        );
    }

    private List<MonthTrend> scopedTrends(
        List<MonthTrend> trends,
        String currency,
        LocalDate periodFrom,
        LocalDate periodTo
    ) {
        List<String> orderedStatuses = java.util.Arrays.stream(PurchaseOrderStatus.values())
            .filter(status -> status != PurchaseOrderStatus.CANCELLED)
            .map(Enum::name)
            .toList();
        List<String> completedStatuses = List.of(
            PurchaseOrderStatus.RECEIVED.name(),
            PurchaseOrderStatus.CLOSED.name()
        );
        LocalDate overdueThrough = LocalDate.now(clock).minusDays(1);
        return trends.stream().map(item -> {
            LocalDate monthFrom = item.month().atDay(1);
            LocalDate monthTo = item.month().atEndOfMonth();
            LocalDate from = monthFrom.isBefore(periodFrom) ? periodFrom : monthFrom;
            LocalDate to = monthTo.isAfter(periodTo) ? periodTo : monthTo;
            return new MonthTrend(
                item.month(),
                item.orderedAmount(),
                item.completedAmount(),
                item.overdueCount(),
                purchaseOrderUrl(from, to, currency, orderedStatuses, false),
                purchaseOrderUrl(from, to, currency, completedStatuses, false),
                purchaseOrderUrl(
                    from,
                    to,
                    currency,
                    ManagementOrderCohort.openStatuses(),
                    true,
                    overdueThrough
                )
            );
        }).toList();
    }

    private String openNonMovingCasesUrl() {
        String statuses = java.util.Arrays.stream(NonMovingStockCaseStatus.values())
            .filter(status -> status != NonMovingStockCaseStatus.CLOSED)
            .filter(status -> status != NonMovingStockCaseStatus.CANCELLED)
            .map(Enum::name)
            .collect(java.util.stream.Collectors.joining(","));
        return "/app/non-moving-stock-dashboard?mode=cases&status=" + statuses;
    }

    private Outcome percentOutcome(
        String key,
        String label,
        Long numerator,
        Long denominator,
        String definition,
        String url
    ) {
        BigDecimal value = percentage(numerator, denominator);
        return new Outcome(
            key, label, value, "PERCENT", null,
            value == null ? Health.UNAVAILABLE : Health.READY,
            definition, CompanyScope.currentCompanyDrilldown(url)
        );
    }

    private Outcome moneyOutcome(
        String key,
        String label,
        BigDecimal value,
        String currency,
        String definition,
        String url
    ) {
        return new Outcome(
            key, label, value, "MONEY", currency,
            value == null ? Health.UNAVAILABLE : Health.READY,
            definition, CompanyScope.currentCompanyDrilldown(url)
        );
    }

    private WorkingCapitalStep capital(
        String key,
        String label,
        BigDecimal value,
        String currency,
        String kind,
        String url
    ) {
        return new WorkingCapitalStep(
            key,
            label,
            value,
            currency,
            kind,
            Health.READY,
            CompanyScope.currentCompanyDrilldown(url)
        );
    }

    private List<ConcentrationCell> scopedConcentration(List<ConcentrationCell> concentration) {
        return concentration.stream().map(item -> new ConcentrationCell(
            item.warehouseId(),
            withoutReferenceCode(item.warehouseLabel()),
            item.materialGroupId(),
            withoutReferenceCode(item.materialGroupLabel()),
            item.value(),
            item.currencyCode(),
            item.sharePercent(),
            CompanyScope.currentCompanyDrilldown(item.drilldownUrl())
        )).toList();
    }

    private List<SupplierExposure> scopedSupplierExposure(List<SupplierExposure> supplierExposure) {
        return supplierExposure.stream().map(item -> new SupplierExposure(
            item.supplierId(),
            item.supplierCode(),
            item.supplierName(),
            item.rating(),
            item.openOutstandingValue(),
            item.currencyCode(),
            item.openScheduleHealthPercent(),
            item.openOrders(),
            item.datedOpenOrders(),
            item.blocked(),
            CompanyScope.currentCompanyDrilldown(item.drilldownUrl())
        )).toList();
    }

    private List<DecisionRow> scopedDecisions(List<DecisionRow> decisions) {
        return decisions.stream().map(item -> new DecisionRow(
            item.id(),
            item.title(),
            item.severity(),
            item.impactValue(),
            item.currencyCode(),
            item.owner(),
            item.dueAt(),
            item.nextAction(),
            CompanyScope.currentCompanyDrilldown(item.drilldownUrl()),
            item.status()
        )).toList();
    }

    private List<SourceHealth> scopedSourceHealth(List<SourceHealth> sourceHealth) {
        return sourceHealth.stream().map(item -> new SourceHealth(
            item.key(),
            item.label(),
            item.status(),
            item.message(),
            item.updatedAt(),
            CompanyScope.currentCompanyDrilldown(item.drilldownUrl())
        )).toList();
    }

    private BigDecimal percentage(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private Long subtract(Long total, Long value) {
        if (total == null || value == null) {
            return null;
        }
        return Math.max(0L, total - value);
    }

    private String withoutReferenceCode(String label) {
        if (label == null) {
            return null;
        }
        String[] parts = label.split("\\s+·\\s+", 2);
        if (parts.length == 2 && parts[0].matches("[A-Z]{2,10}[-_A-Z0-9]*\\d[-_A-Z0-9]*")) {
            return parts[1];
        }
        return label;
    }

    private void validate(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BadRequestException("Management overview period start must not be after period end");
        }
        if (ChronoUnit.DAYS.between(from, to) > 730) {
            throw new BadRequestException("Management overview period must not exceed 731 days");
        }
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return null;
        }
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new BadRequestException("Display currency must be a three-letter ISO code");
        }
        return normalized;
    }
}
