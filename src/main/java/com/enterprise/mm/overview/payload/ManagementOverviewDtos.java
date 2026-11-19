package com.enterprise.mm.overview.payload;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public final class ManagementOverviewDtos {
    private ManagementOverviewDtos() {
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
        UUID companyId,
        String scopeLabel,
        String displayCurrency,
        Health status,
        List<String> messages
    ) {
    }

    public record DatasetMetadata(
        String key,
        String dataBasis,
        LocalDate periodFrom,
        LocalDate periodTo,
        Instant asOf,
        String definition
    ) {
    }

    public record Outcome(
        String key,
        String label,
        BigDecimal value,
        String unit,
        String currencyCode,
        Health health,
        String definition,
        String drilldownUrl
    ) {
    }

    public record MonthTrend(
        YearMonth month,
        BigDecimal orderedAmount,
        BigDecimal completedAmount,
        long overdueCount,
        String orderedDrilldownUrl,
        String completedDrilldownUrl,
        String overdueDrilldownUrl
    ) {
    }

    public record WorkingCapitalStep(
        String key,
        String label,
        BigDecimal value,
        String currencyCode,
        String kind,
        Health health,
        String drilldownUrl
    ) {
    }

    public record ConcentrationCell(
        UUID warehouseId,
        String warehouseLabel,
        UUID materialGroupId,
        String materialGroupLabel,
        BigDecimal value,
        String currencyCode,
        BigDecimal sharePercent,
        String drilldownUrl
    ) {
    }

    public record SupplierExposure(
        UUID supplierId,
        String supplierCode,
        String supplierName,
        BigDecimal rating,
        BigDecimal openOutstandingValue,
        String currencyCode,
        BigDecimal openScheduleHealthPercent,
        long openOrders,
        long datedOpenOrders,
        boolean blocked,
        String drilldownUrl
    ) {
    }

    public record LeadTimeBucket(String key, String label, long count) {
    }

    public record DecisionRow(
        String id,
        String title,
        String severity,
        BigDecimal impactValue,
        String currencyCode,
        String owner,
        LocalDate dueAt,
        String nextAction,
        String drilldownUrl,
        String status
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

    public record ManagementOverview(
        Metadata metadata,
        List<DatasetMetadata> datasets,
        List<Outcome> outcomes,
        List<MonthTrend> trends,
        List<WorkingCapitalStep> workingCapital,
        List<ConcentrationCell> concentration,
        List<SupplierExposure> supplierExposure,
        List<LeadTimeBucket> leadTime,
        List<DecisionRow> decisions,
        List<SourceHealth> sourceHealth
    ) {
    }
}
