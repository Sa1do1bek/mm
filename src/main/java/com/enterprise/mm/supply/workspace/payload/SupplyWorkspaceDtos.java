package com.enterprise.mm.supply.workspace.payload;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentSort;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyDueDateSource;
import com.enterprise.mm.supply.workspace.enums.SupplyEscalationState;
import com.enterprise.mm.supply.workspace.enums.SupplyLens;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.enums.SupplyRiskKey;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.enums.SupplySourceState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit transport contracts for the Supply workspace.  The records deliberately
 * carry availability and limitation metadata so callers never have to infer that a
 * missing value means zero.
 */
public final class SupplyWorkspaceDtos {
    private SupplyWorkspaceDtos() {
    }

    public record AppliedScope(
        String tenantId,
        Set<UUID> companyIds,
        Set<UUID> warehouseIds,
        boolean warehouseScoped
    ) {
        public AppliedScope {
            companyIds = companyIds == null ? Set.of() : Set.copyOf(companyIds);
            warehouseIds = warehouseIds == null ? Set.of() : Set.copyOf(warehouseIds);
        }
    }

    public record AppliedFilters(
        Instant asOf,
        LocalDate from,
        LocalDate to,
        SupplyLens lens,
        UUID ownerUserId,
        UUID companyId,
        UUID supplierId,
        UUID purchaseOrderId,
        UUID contractId,
        UUID warehouseId,
        String transportMode,
        String currencyCode,
        String status,
        SupplyRiskKey risk,
        SupplyQueueKey queue,
        String search,
        SupplyCommitmentSort sort
    ) {
    }

    public record EntityRef(UUID id, String code, String displayName, String status) {
    }

    public record MoneyValue(
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal originalAmount,
        String currency,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal baseAmount,
        String baseCurrency,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal rate,
        LocalDate rateDate,
        String rateSource,
        boolean conversionAvailable
    ) {
    }

    public record QuantityValue(
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal value,
        String uomCode
    ) {
    }

    public record DeepLinkTarget(
        String moduleKey,
        UUID recordId,
        String commitmentKey,
        Map<String, String> query
    ) {
        public DeepLinkTarget {
            query = query == null ? Map.of() : Map.copyOf(query);
        }
    }

    public record AllowedAction(
        String key,
        String labelKey,
        String tone,
        boolean requiresReason,
        boolean requiresEvidence,
        Long expectedVersion,
        DeepLinkTarget target
    ) {
    }

    public record SourceFreshness(
        String source,
        Instant generatedAt,
        Instant lastSuccessfulAt,
        SupplySourceState state,
        String messageKey
    ) {
    }

    public record UnavailableReason(String code, String messageKey) {
    }

    public record PartialError(
        String source,
        String code,
        String messageKey,
        boolean retryable
    ) {
    }

    public record MetricComparison(
        boolean available,
        Long previousCount,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal percentageChange,
        UnavailableReason reason
    ) {
    }

    public record DrilldownTarget(
        SupplyQueueKey queue,
        AppliedFilters filters,
        String route
    ) {
    }

    public record WorkspaceMetric(
        SupplyQueueKey key,
        Long count,
        List<MoneyValue> moneyByCurrency,
        List<QuantityValue> quantityByUom,
        MetricComparison comparison,
        DrilldownTarget drilldown,
        String definitionVersion,
        SupplyDataQuality availability,
        List<UnavailableReason> reasons
    ) {
        public WorkspaceMetric {
            moneyByCurrency = immutable(moneyByCurrency);
            quantityByUom = immutable(quantityByUom);
            reasons = immutable(reasons);
        }
    }

    public record QueueSummary(
        SupplyQueueKey key,
        Long count,
        DrilldownTarget drilldown,
        SupplySeverity severity,
        SupplyDataQuality availability
    ) {
    }

    public record AgendaDaySummary(
        LocalDate date,
        Long count,
        SupplySeverity highestSeverity,
        DrilldownTarget drilldown
    ) {
    }

    public record AnalyticsAvailability(
        String key,
        SupplyDataQuality availability,
        List<UnavailableReason> reasons
    ) {
        public AnalyticsAvailability {
            reasons = immutable(reasons);
        }
    }

    public record SupplyOverviewResponse(
        AppliedScope appliedScope,
        AppliedFilters appliedFilters,
        List<WorkspaceMetric> metrics,
        List<QueueSummary> queues,
        List<AgendaDaySummary> agenda,
        List<AnalyticsAvailability> analytics,
        List<SourceFreshness> sourceFreshness,
        List<PartialError> partialErrors,
        Instant generatedAt,
        String timeZone,
        String definitionVersion
    ) {
        public SupplyOverviewResponse {
            metrics = immutable(metrics);
            queues = immutable(queues);
            agenda = immutable(agenda);
            analytics = immutable(analytics);
            sourceFreshness = immutable(sourceFreshness);
            partialErrors = immutable(partialErrors);
        }
    }

    public record AssignmentSummary(
        EntityRef owner,
        String ownerRole,
        LocalDate dueDate,
        SupplyDueDateSource dueDateSource,
        SupplySeverity severity,
        SupplyEscalationState escalationState,
        Integer escalationLevel,
        String reason,
        Long version,
        SupplyDataQuality availability,
        List<UnavailableReason> reasons
    ) {
        public AssignmentSummary {
            reasons = immutable(reasons);
        }

        public AssignmentSummary(
            EntityRef owner,
            String ownerRole,
            LocalDate dueDate,
            SupplySeverity severity,
            Long version,
            SupplyDataQuality availability,
            List<UnavailableReason> reasons
        ) {
            this(
                owner, ownerRole, dueDate, null, severity, null, null, null,
                version, availability, reasons);
        }
    }

    public record CommitmentQuantities(
        QuantityValue planned,
        QuantityValue open,
        QuantityValue accepted
    ) {
    }

    public record SupplyCommitmentRow(
        String commitmentKey,
        String encodedCommitmentKey,
        EntityRef purchaseOrder,
        EntityRef supplier,
        EntityRef destinationWarehouse,
        UUID contractId,
        LocalDate promisedDate,
        long overdueDays,
        MoneyValue openAmount,
        CommitmentQuantities quantities,
        String currentMilestone,
        String blockerCode,
        String blockerMessageKey,
        SupplySeverity severity,
        SupplyDataQuality dataQuality,
        AnalyticsAvailability asnReadiness,
        List<UnavailableReason> limitations,
        AssignmentSummary assignment,
        List<AllowedAction> allowedActions,
        DeepLinkTarget primaryTarget
    ) {
        public SupplyCommitmentRow {
            limitations = immutable(limitations);
            allowedActions = immutable(allowedActions);
        }
    }

    public record SupplyCommitmentPageResponse(
        AppliedScope appliedScope,
        AppliedFilters appliedFilters,
        List<SupplyCommitmentRow> items,
        long totalElements,
        int page,
        int size,
        int totalPages,
        List<SourceFreshness> sourceFreshness,
        List<PartialError> partialErrors,
        Instant generatedAt,
        String timeZone,
        String definitionVersion
    ) {
        public SupplyCommitmentPageResponse {
            items = immutable(items);
            sourceFreshness = immutable(sourceFreshness);
            partialErrors = immutable(partialErrors);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
