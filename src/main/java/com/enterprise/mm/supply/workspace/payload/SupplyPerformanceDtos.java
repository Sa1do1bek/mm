package com.enterprise.mm.supply.workspace.payload;

import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyPerformanceMetricKey;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedFilters;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedScope;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.DeepLinkTarget;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.PartialError;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SourceFreshness;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.UnavailableReason;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact transport contract for bounded, source-backed Supply performance series. */
public final class SupplyPerformanceDtos {
    private SupplyPerformanceDtos() {
    }

    public record DenominatorMetadata(
        long eligibleCount,
        long excludedCount,
        int minimumSampleSize,
        Map<String, Long> excludedByReason,
        LocalDate cohortFrom,
        LocalDate cohortTo,
        String cohortDateBasis
    ) {
        public DenominatorMetadata {
            excludedByReason = excludedByReason == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(excludedByReason));
        }
    }

    public record PerformancePoint(
        LocalDate weekStart,
        String isoWeek,
        long sampleSize,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal value,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal median,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal p90,
        SupplyDataQuality availability,
        List<UnavailableReason> reasons,
        DeepLinkTarget drilldownTarget
    ) {
        public PerformancePoint {
            reasons = immutable(reasons);
        }
    }

    public record PerformanceSeries(
        SupplyPerformanceMetricKey key,
        String unit,
        SupplyDataQuality availability,
        List<PerformancePoint> points,
        DenominatorMetadata denominator,
        List<UnavailableReason> reasons,
        String definitionVersion
    ) {
        public PerformanceSeries {
            points = immutable(points);
            reasons = immutable(reasons);
        }
    }

    public record SupplyPerformanceResponse(
        AppliedScope appliedScope,
        AppliedFilters appliedFilters,
        List<PerformanceSeries> series,
        List<SourceFreshness> sourceFreshness,
        List<PartialError> partialErrors,
        Instant generatedAt,
        String timeZone,
        String definitionVersion
    ) {
        public SupplyPerformanceResponse {
            series = immutable(series);
            sourceFreshness = immutable(sourceFreshness);
            partialErrors = immutable(partialErrors);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
