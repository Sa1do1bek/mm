package com.enterprise.mm.supply.workspace.payload;

import com.enterprise.mm.supply.workspace.enums.SupplyAvailability;
import com.enterprise.mm.supply.workspace.enums.SupplyCalendarLane;
import com.enterprise.mm.supply.workspace.enums.SupplyDateBasis;
import com.enterprise.mm.supply.workspace.enums.SupplyDeliverySource;
import com.enterprise.mm.supply.workspace.enums.SupplyRiskKey;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AllowedAction;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedFilters;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedScope;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.DeepLinkTarget;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.EntityRef;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.MoneyValue;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.PartialError;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SourceFreshness;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.UnavailableReason;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SupplyCalendarDtos {
    private SupplyCalendarDtos() {
    }

    /**
     * All current source dates are operational {@link LocalDate} values. Keeping the
     * wire value date-only prevents a browser or server timezone from shifting the
     * business day; {@code allDay} makes that semantic explicit to clients.
     */
    public record CalendarEvent(
        String id,
        SupplyDeliverySource source,
        SupplyCalendarLane lane,
        LocalDate dateTime,
        boolean allDay,
        SupplyDateBasis dateBasis,
        String documentType,
        UUID documentId,
        String documentCode,
        String documentName,
        String commitmentKey,
        EntityRef supplier,
        EntityRef purchaseOrder,
        EntityRef contract,
        MoneyValue money,
        String status,
        SupplyRiskKey risk,
        EntityRef owner,
        AllowedAction nextAction,
        DeepLinkTarget primaryTarget,
        DeepLinkTarget commitmentTarget
    ) {
    }

    public record CalendarLaneSummary(
        SupplyCalendarLane lane,
        Long eventCount,
        List<MoneyValue> moneyByCurrency,
        SupplySeverity highestSeverity,
        SupplyAvailability availability,
        List<UnavailableReason> reasons
    ) {
        public CalendarLaneSummary {
            moneyByCurrency = immutable(moneyByCurrency);
            reasons = immutable(reasons);
        }
    }

    public record SectionFilterSupport(
        SupplyCalendarLane lane,
        List<String> supportedFilters,
        List<String> unsupportedActiveFilters
    ) {
        public SectionFilterSupport {
            supportedFilters = immutable(supportedFilters);
            unsupportedActiveFilters = immutable(unsupportedActiveFilters);
        }
    }

    public record SupplyCalendarResponse(
        AppliedScope appliedScope,
        AppliedFilters appliedFilters,
        List<CalendarEvent> events,
        List<CalendarLaneSummary> laneSummaries,
        List<SectionFilterSupport> filterSupport,
        List<SourceFreshness> sourceFreshness,
        List<PartialError> partialErrors,
        Instant generatedAt,
        String timeZone,
        String definitionVersion
    ) {
        public SupplyCalendarResponse {
            events = immutable(events);
            laneSummaries = immutable(laneSummaries);
            filterSupport = immutable(filterSupport);
            sourceFreshness = immutable(sourceFreshness);
            partialErrors = immutable(partialErrors);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
