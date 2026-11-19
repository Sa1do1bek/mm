package com.enterprise.mm.overview.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class OperationalOverviewDtos {
    private OperationalOverviewDtos() {
    }

    public enum Health {
        READY,
        PARTIAL,
        UNAVAILABLE
    }

    public enum SourceStatus {
        HEALTHY,
        DEGRADED,
        UNAVAILABLE
    }

    public record Metadata(
        Instant generatedAt,
        LocalDate periodFrom,
        LocalDate periodTo,
        String dataBasis,
        Instant asOf,
        UUID companyId,
        String scopeLabel,
        Health status,
        List<String> messages
    ) {
    }

    public record Outcome(
        String key,
        String label,
        BigDecimal value,
        String unit,
        Health health,
        String definition,
        String queueKey,
        String drilldownUrl,
        List<SourceDrilldown> drilldowns
    ) {
        public Outcome {
            drilldowns = drilldowns == null ? List.of() : List.copyOf(drilldowns);
        }
    }

    public record SourceDrilldown(
        String key,
        String label,
        Long count,
        Health health,
        String drilldownUrl
    ) {
    }

    public record ProcessStage(
        String key,
        String label,
        long count,
        Health health,
        String queueKey,
        String drilldownUrl
    ) {
    }

    public record AgingBucket(
        String key,
        String label,
        long count,
        String severity,
        String queueKey,
        String drilldownUrl
    ) {
    }

    public record TrendPoint(
        LocalDate date,
        long newCount,
        long resolvedCount,
        long overdueCount
    ) {
    }

    public record SourceHealth(
        String key,
        String label,
        SourceStatus status,
        String message,
        Instant updatedAt,
        String drilldownUrl
    ) {
    }

    public record RawException(
        String id,
        String queueKey,
        String title,
        String severity,
        String impact,
        String owner,
        LocalDate dueAt,
        String nextAction,
        String sourceModule,
        String drilldownUrl,
        String status
    ) {
    }

    public record PriorityException(
        String id,
        String queueKey,
        String title,
        String severity,
        String impact,
        String owner,
        LocalDate dueAt,
        Long ageDays,
        String nextAction,
        String sourceModule,
        String drilldownUrl,
        String status
    ) {
    }

    public record OperationalOverview(
        Metadata metadata,
        List<Outcome> outcomes,
        List<ProcessStage> stages,
        List<AgingBucket> aging,
        List<TrendPoint> trend,
        List<SourceHealth> sourceHealth,
        List<PriorityException> priorityExceptions
    ) {
    }
}
