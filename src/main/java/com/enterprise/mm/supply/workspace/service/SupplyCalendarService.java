package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyAvailability;
import com.enterprise.mm.supply.workspace.enums.SupplyCalendarLane;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyRiskKey;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.enums.SupplySourceState;
import com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.CalendarEvent;
import com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.CalendarLaneSummary;
import com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.SectionFilterSupport;
import com.enterprise.mm.supply.workspace.payload.SupplyCalendarDtos.SupplyCalendarResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedScope;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.MoneyValue;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.PartialError;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SourceFreshness;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.UnavailableReason;
import com.enterprise.mm.supply.workspace.repository.SupplyCalendarQueryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class SupplyCalendarService {
    private static final long MAX_RANGE_DAYS = 100;
    private static final String DEFINITION_VERSION = "supply-workspace.v1";

    private static final List<String> FILTER_KEYS = List.of(
        "lens", "ownerUserId", "companyId", "supplierId", "purchaseOrderId", "contractId",
        "warehouseId", "transportMode", "currencyCode", "status", "risk", "queue", "search");

    private static final Map<SupplyCalendarLane, Set<String>> SUPPORTED_FILTERS = supportedFilters();

    private final SupplyCalendarQueryRepository repository;
    private final SupplyWorkspaceAccessPolicy accessPolicy;
    private final Clock clock;

    public SupplyCalendarService(
        SupplyCalendarQueryRepository repository,
        SupplyWorkspaceAccessPolicy accessPolicy,
        Clock clock
    ) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    public SupplyCalendarResponse calendar(SupplyWorkspaceQuery query) {
        return calendar(query, EnumSet.allOf(SupplyCalendarLane.class));
    }

    public SupplyCalendarResponse calendar(
        SupplyWorkspaceQuery query,
        Set<SupplyCalendarLane> requestedLanes
    ) {
        validate(query);
        accessPolicy.requireReadable(query.lens());
        SupplyWorkspaceQuery effective = accessPolicy.bindAuthenticatedIdentity(query);
        if (effective == null) {
            throw new IllegalStateException("Authenticated Supply calendar query is required");
        }
        validate(effective);

        EnumSet<SupplyCalendarLane> lanes = requestedLanes == null || requestedLanes.isEmpty()
            ? EnumSet.allOf(SupplyCalendarLane.class)
            : EnumSet.copyOf(requestedLanes);
        Instant generatedAt = clock.instant();
        List<CalendarEvent> events = new ArrayList<>();
        List<CalendarLaneSummary> summaries = new ArrayList<>();
        List<SectionFilterSupport> filterSupport = new ArrayList<>();
        List<SourceFreshness> freshness = new ArrayList<>();
        List<PartialError> partialErrors = new ArrayList<>();

        for (SupplyCalendarLane lane : SupplyCalendarLane.values()) {
            if (!lanes.contains(lane)) {
                continue;
            }
            List<String> supported = ordered(SUPPORTED_FILTERS.get(lane));
            List<String> unsupportedActive = unsupportedActive(effective, SUPPORTED_FILTERS.get(lane));
            filterSupport.add(new SectionFilterSupport(lane, supported, unsupportedActive));

            boolean warehouseScopeUnsupported = lane == SupplyCalendarLane.PAYMENT
                && effective.warehouseScoped();
            if (!unsupportedActive.isEmpty() || warehouseScopeUnsupported) {
                String code = warehouseScopeUnsupported && unsupportedActive.isEmpty()
                    ? "WAREHOUSE_SCOPE_UNSUPPORTED"
                    : "UNSUPPORTED_ACTIVE_FILTER";
                UnavailableReason reason = reason(code, code.equals("WAREHOUSE_SCOPE_UNSUPPORTED")
                    ? "supply.calendar.filter.warehouseScopeUnsupported"
                    : "supply.calendar.filter.unsupportedActive");
                summaries.add(new CalendarLaneSummary(
                    lane, null, List.of(), null, SupplyAvailability.UNAVAILABLE, List.of(reason)));
                freshness.add(new SourceFreshness(
                    lane.name(), generatedAt, null, SupplySourceState.UNAVAILABLE,
                    "supply.calendar.source.filterUnsupported"));
                continue;
            }

            try {
                List<CalendarEvent> laneEvents = queryLane(lane, effective);
                events.addAll(laneEvents);
                CalendarLaneSummary summary = summarize(lane, laneEvents);
                summaries.add(summary);
                freshness.add(new SourceFreshness(
                    lane.name(), generatedAt, generatedAt,
                    summary.availability() == SupplyAvailability.PARTIAL
                        ? SupplySourceState.PARTIAL
                        : SupplySourceState.FRESH,
                    summary.availability() == SupplyAvailability.PARTIAL
                        ? "supply.calendar.source.partial"
                        : "supply.calendar.source.fresh"));
            } catch (RuntimeException exception) {
                summaries.add(new CalendarLaneSummary(
                    lane,
                    null,
                    List.of(),
                    null,
                    SupplyAvailability.UNAVAILABLE,
                    List.of(reason("CALENDAR_SOURCE_FAILED", "supply.calendar.source.failed"))));
                freshness.add(new SourceFreshness(
                    lane.name(), generatedAt, null, SupplySourceState.FAILED,
                    "supply.calendar.source.failed"));
                partialErrors.add(new PartialError(
                    lane.name(), "CALENDAR_SOURCE_FAILED", "supply.calendar.source.failed", true));
            }
        }

        events.sort(Comparator
            .comparing(CalendarEvent::dateTime)
            .thenComparing(event -> event.lane().ordinal())
            .thenComparing(CalendarEvent::id));
        return new SupplyCalendarResponse(
            new AppliedScope(
                effective.tenantId(), effective.authorizedCompanyIds(),
                effective.authorizedWarehouseIds(), effective.warehouseScoped()),
            SupplyWorkspaceQueryService.appliedFilters(effective),
            events,
            summaries,
            filterSupport,
            freshness,
            partialErrors,
            generatedAt,
            effective.timeZone().getId(),
            DEFINITION_VERSION);
    }

    private List<CalendarEvent> queryLane(
        SupplyCalendarLane lane,
        SupplyWorkspaceQuery query
    ) {
        List<CalendarEvent> result = switch (lane) {
            case DELIVERY -> repository.deliveryEvents(query);
            case ASN -> repository.asnEvents(query);
            case IMPORT_CUSTOMS -> repository.importAndCustomsEvents(query);
            case INVOICE -> repository.invoiceEvents(query);
            case PAYMENT -> repository.paymentEvents(query);
        };
        if (result == null) {
            return List.of();
        }
        return result.stream().filter(event -> event != null && event.lane() == lane).toList();
    }

    private CalendarLaneSummary summarize(
        SupplyCalendarLane lane,
        List<CalendarEvent> events
    ) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        Map<String, UnavailableReason> limitations = new LinkedHashMap<>();
        SupplySeverity highestSeverity = null;
        for (CalendarEvent event : events) {
            highestSeverity = maximum(highestSeverity, severity(event.risk()));
            MoneyValue money = event.money();
            if (money == null) {
                continue;
            }
            if (money.originalAmount() == null) {
                limitations.putIfAbsent(
                    "MISSING_AMOUNT", reason("MISSING_AMOUNT", "supply.calendar.money.amountMissing"));
                continue;
            }
            String currency = money.currency() == null
                ? null
                : money.currency().trim().toUpperCase(Locale.ROOT);
            if (currency == null || currency.isEmpty()) {
                limitations.putIfAbsent(
                    "MISSING_CURRENCY", reason("MISSING_CURRENCY", "supply.calendar.money.currencyMissing"));
                continue;
            }
            if (!currency.matches("[A-Z]{3}")) {
                limitations.putIfAbsent(
                    "INVALID_CURRENCY", reason("INVALID_CURRENCY", "supply.calendar.money.currencyInvalid"));
                continue;
            }
            totals.merge(currency, money.originalAmount(), BigDecimal::add);
        }
        List<MoneyValue> moneyByCurrency = totals.entrySet().stream()
            .map(entry -> new MoneyValue(
                entry.getValue(), entry.getKey(), null, null, null, null, null, false))
            .toList();
        SupplyAvailability availability = limitations.isEmpty()
            ? SupplyAvailability.AUTHORITATIVE
            : SupplyAvailability.PARTIAL;
        return new CalendarLaneSummary(
            lane,
            (long) events.size(),
            moneyByCurrency,
            highestSeverity,
            availability,
            new ArrayList<>(limitations.values()));
    }

    private SupplySeverity severity(SupplyRiskKey risk) {
        if (risk == null) {
            return null;
        }
        return switch (risk) {
            case OVERDUE, CUSTOMS_BLOCKED, INTEGRATION_FAILURE -> SupplySeverity.BLOCKING;
            case ASN_MISSING, IMPORT_ETA_RISK, UNASSIGNED -> SupplySeverity.WARNING;
        };
    }

    private SupplySeverity maximum(SupplySeverity current, SupplySeverity candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.ordinal() > current.ordinal() ? candidate : current;
    }

    private List<String> unsupportedActive(
        SupplyWorkspaceQuery query,
        Set<String> supported
    ) {
        Map<String, Boolean> active = new LinkedHashMap<>();
        active.put("lens", query.lens() == SupplyLens.MY_WORK);
        active.put("ownerUserId", query.ownerUserId() != null);
        active.put("companyId", query.companyId() != null);
        active.put("supplierId", query.supplierId() != null);
        active.put("purchaseOrderId", query.purchaseOrderId() != null);
        active.put("contractId", query.contractId() != null);
        active.put("warehouseId", query.warehouseId() != null);
        active.put("transportMode", query.transportMode() != null);
        active.put("currencyCode", query.currencyCode() != null);
        active.put("status", query.status() != null);
        active.put("risk", query.risk() != null);
        active.put("queue", query.queue() != null);
        active.put("search", query.search() != null);
        return FILTER_KEYS.stream()
            .filter(key -> Boolean.TRUE.equals(active.get(key)) && !supported.contains(key))
            .toList();
    }

    private List<String> ordered(Set<String> filters) {
        return FILTER_KEYS.stream().filter(filters::contains).toList();
    }

    private void validate(SupplyWorkspaceQuery query) {
        if (query == null || query.from() == null || query.to() == null) {
            throw bad(
                "SUPPLY_CALENDAR_RANGE_REQUIRED",
                "Both from and to are required for the Supply calendar");
        }
        long days = ChronoUnit.DAYS.between(query.from(), query.to());
        if (days <= 0) {
            throw bad("SUPPLY_CALENDAR_RANGE_INVALID", "to must be after from");
        }
        if (days > MAX_RANGE_DAYS) {
            throw bad(
                "SUPPLY_CALENDAR_RANGE_TOO_LARGE",
                "Supply calendar range cannot exceed 100 days");
        }
    }

    private BadRequestException bad(String code, String message) {
        return new BadRequestException(
            code + ": " + message,
            "supply.calendar.request.invalid",
            code);
    }

    private UnavailableReason reason(String code, String messageKey) {
        return new UnavailableReason(code, messageKey);
    }

    private static Map<SupplyCalendarLane, Set<String>> supportedFilters() {
        Map<SupplyCalendarLane, Set<String>> result = new EnumMap<>(SupplyCalendarLane.class);
        result.put(SupplyCalendarLane.DELIVERY, Set.of(
            "lens", "ownerUserId", "companyId", "supplierId", "purchaseOrderId", "contractId",
            "warehouseId", "currencyCode", "status", "risk", "queue", "search"));
        result.put(SupplyCalendarLane.ASN, Set.of(
            "companyId", "supplierId", "purchaseOrderId", "contractId", "warehouseId",
            "transportMode", "currencyCode", "status", "risk", "queue", "search"));
        result.put(SupplyCalendarLane.IMPORT_CUSTOMS, Set.of(
            "companyId", "supplierId", "purchaseOrderId", "contractId", "warehouseId",
            "transportMode", "currencyCode", "status", "risk", "queue", "search"));
        result.put(SupplyCalendarLane.INVOICE, Set.of(
            "companyId", "supplierId", "purchaseOrderId", "contractId", "warehouseId",
            "currencyCode", "status", "risk", "queue", "search"));
        result.put(SupplyCalendarLane.PAYMENT, Set.of(
            "companyId", "supplierId", "purchaseOrderId", "contractId", "currencyCode",
            "status", "risk", "search"));
        return Map.copyOf(result);
    }
}
