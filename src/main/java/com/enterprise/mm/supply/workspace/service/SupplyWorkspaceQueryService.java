package com.enterprise.mm.supply.workspace.service;

import com.enterprise.mm.supply.workspace.domain.SupplyCommitmentKeyCodec;
import com.enterprise.mm.supply.workspace.domain.SupplyWorkspaceQuery;
import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import com.enterprise.mm.supply.workspace.enums.SupplyDataQuality;
import com.enterprise.mm.supply.workspace.enums.SupplyQueueKey;
import com.enterprise.mm.supply.workspace.enums.SupplySeverity;
import com.enterprise.mm.supply.workspace.enums.SupplySourceState;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AgendaDaySummary;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AllowedAction;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AnalyticsAvailability;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedFilters;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AppliedScope;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.AssignmentSummary;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.CommitmentQuantities;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.DeepLinkTarget;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.DrilldownTarget;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.EntityRef;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.MetricComparison;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.MoneyValue;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.PartialError;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.QuantityValue;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.QueueSummary;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SourceFreshness;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SupplyCommitmentPageResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SupplyCommitmentRow;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.SupplyOverviewResponse;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.UnavailableReason;
import com.enterprise.mm.supply.workspace.payload.SupplyWorkspaceDtos.WorkspaceMetric;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentQueryRepository;
import com.enterprise.mm.supply.workspace.repository.SupplyCommitmentRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplyWorkspaceQueryService {
    public static final String DEFINITION_VERSION = "supply-workspace.v1";
    private static final String COMMITMENTS_ROUTE = "/app/category/menuSupply/commitments";
    private static final List<SupplyQueueKey> ATTENTION_METRICS = List.of(
        SupplyQueueKey.OVERDUE_COMMITMENTS,
        SupplyQueueKey.ARRIVING_7D_WITHOUT_CONFIRMED_ASN,
        SupplyQueueKey.ACTIVE_IMPORT_ETA_RISK,
        SupplyQueueKey.CUSTOMS_DOCUMENT_BLOCKED,
        SupplyQueueKey.INTEGRATION_FAILED);

    private final SupplyCommitmentQueryRepository repository;
    private final SupplyCommitmentKeyCodec keyCodec;
    private final SupplyWorkspaceAccessPolicy accessPolicy;
    private final SupplyMoneyService moneyService;
    private final SupplySlaPolicy slaPolicy;
    private final SupplyActionPolicy actionPolicy;
    private final Clock clock;

    public SupplyWorkspaceQueryService(
        SupplyCommitmentQueryRepository repository,
        SupplyCommitmentKeyCodec keyCodec,
        SupplyWorkspaceAccessPolicy accessPolicy,
        SupplyMoneyService moneyService,
        SupplySlaPolicy slaPolicy,
        SupplyActionPolicy actionPolicy,
        Clock clock
    ) {
        this.repository = repository;
        this.keyCodec = keyCodec;
        this.accessPolicy = accessPolicy;
        this.moneyService = moneyService;
        this.slaPolicy = slaPolicy;
        this.actionPolicy = actionPolicy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SupplyOverviewResponse overview(SupplyWorkspaceQuery query) {
        SupplyWorkspaceQuery effective = effectiveQuery(query);
        Instant generatedAt = clock.instant();
        List<PartialError> partialErrors = new ArrayList<>();
        Map<SupplyQueueKey, CountResult> counts = new EnumMap<>(SupplyQueueKey.class);
        for (SupplyQueueKey key : SupplyQueueKey.values()) {
            counts.put(key, count(effective.withQueue(key), partialErrors));
        }

        List<WorkspaceMetric> metrics = ATTENTION_METRICS.stream()
            .map(key -> metric(effective.withQueue(key), key, counts.get(key), partialErrors))
            .toList();
        List<QueueSummary> queues = java.util.Arrays.stream(SupplyQueueKey.values())
            .map(key -> new QueueSummary(
                key,
                counts.get(key).value(),
                drilldown(effective.withQueue(key), key),
                queueSeverity(key),
                queueAvailability(key, counts.get(key))))
            .toList();
        List<AgendaDaySummary> agenda = agenda(effective, partialErrors);
        appendUnlinkedImportCoverage(effective, partialErrors);

        List<AnalyticsAvailability> analytics = List.of(new AnalyticsAvailability(
            "OTIF",
            SupplyDataQuality.UNAVAILABLE,
            List.of(reason("RECEIPT_ALLOCATION_INCOMPLETE", "supply.workspace.analytics.otif.receiptAllocationIncomplete"))));
        SourceFreshness freshness = freshness(generatedAt, partialErrors);
        return new SupplyOverviewResponse(
            appliedScope(effective),
            appliedFilters(effective),
            metrics,
            queues,
            agenda,
            analytics,
            List.of(freshness),
            partialErrors,
            generatedAt,
            effective.timeZone().getId(),
            DEFINITION_VERSION);
    }

    @Transactional(readOnly = true)
    public SupplyCommitmentPageResponse commitments(SupplyWorkspaceQuery query, Pageable pageable) {
        SupplyWorkspaceQuery effective = effectiveQuery(query);
        Instant generatedAt = clock.instant();
        Page<SupplyCommitmentRecord> result = repository.search(effective, pageable);
        List<SupplyCommitmentRow> rows = result.getContent().stream()
            .map(record -> row(record, effective))
            .toList();
        return new SupplyCommitmentPageResponse(
            appliedScope(effective),
            appliedFilters(effective),
            rows,
            result.getTotalElements(),
            result.getNumber(),
            result.getSize(),
            result.getTotalPages(),
            List.of(freshness(generatedAt, List.of())),
            List.of(),
            generatedAt,
            effective.timeZone().getId(),
            DEFINITION_VERSION);
    }

    public static AppliedFilters appliedFilters(SupplyWorkspaceQuery query) {
        return new AppliedFilters(
            query.asOf(), query.from(), query.to(), query.lens(), query.ownerUserId(),
            query.companyId(), query.supplierId(), query.purchaseOrderId(), query.contractId(),
            query.warehouseId(), query.transportMode(), query.currencyCode(), query.status(),
            query.risk(), query.queue(), query.search(), query.sort());
    }

    private WorkspaceMetric metric(
        SupplyWorkspaceQuery metricQuery,
        SupplyQueueKey key,
        CountResult count,
        List<PartialError> partialErrors
    ) {
        List<MoneyValue> money = List.of();
        List<QuantityValue> quantity = List.of();
        List<UnavailableReason> reasons = new ArrayList<>();
        SupplyDataQuality supportingAvailability = SupplyDataQuality.AUTHORITATIVE;

        if (key == SupplyQueueKey.OVERDUE_COMMITMENTS || key == SupplyQueueKey.ACTIVE_IMPORT_ETA_RISK) {
            try {
                SupplyMoneyService.MoneyBreakdown breakdown = moneyService.group(
                    repository.openExposureByCurrency(metricQuery));
                money = breakdown.values();
                reasons.addAll(breakdown.reasons());
                supportingAvailability = breakdown.availability();
            } catch (DataAccessException exception) {
                supportingAvailability = SupplyDataQuality.UNAVAILABLE;
                reasons.add(reason("OPEN_EXPOSURE_SOURCE_FAILED", "supply.workspace.metric.openExposureUnavailable"));
                partialErrors.add(sourceFailure(key, "OPEN_EXPOSURE_SOURCE_FAILED"));
            }
        }
        if (key == SupplyQueueKey.ARRIVING_7D_WITHOUT_CONFIRMED_ASN) {
            try {
                List<SupplyCommitmentRecord.QuantityValue> grouped = repository.openQuantityByUom(metricQuery);
                quantity = grouped.stream()
                    .map(value -> new QuantityValue(value.value(), value.uomCode()))
                    .toList();
                if (grouped.stream().anyMatch(value -> value.value() == null || value.uomCode() == null)) {
                    supportingAvailability = SupplyDataQuality.PARTIAL;
                    reasons.add(reason("INCOMPLETE_OPEN_QUANTITY", "supply.workspace.metric.openQuantityIncomplete"));
                }
            } catch (DataAccessException exception) {
                supportingAvailability = SupplyDataQuality.UNAVAILABLE;
                reasons.add(reason("OPEN_QUANTITY_SOURCE_FAILED", "supply.workspace.metric.openQuantityUnavailable"));
                partialErrors.add(sourceFailure(key, "OPEN_QUANTITY_SOURCE_FAILED"));
            }
            reasons.add(reason(
                "HEADER_ONLY_SCHEDULE_ALLOCATION",
                "supply.workspace.asnReadiness.headerOnlyScheduleAllocation"));
            if (supportingAvailability == SupplyDataQuality.AUTHORITATIVE) {
                supportingAvailability = SupplyDataQuality.PARTIAL;
            }
        }
        if (key == SupplyQueueKey.OVERDUE_COMMITMENTS) {
            reasons.add(reason(
                "UNKNOWN_OPEN_QUANTITY_EXCLUDED",
                "supply.workspace.overdue.unknownOpenQuantityExcluded"));
            if (supportingAvailability == SupplyDataQuality.AUTHORITATIVE) {
                supportingAvailability = SupplyDataQuality.PARTIAL;
            }
        }
        if (key == SupplyQueueKey.CUSTOMS_DOCUMENT_BLOCKED) {
            reasons.add(reason(
                "DOCUMENT_CHECKLIST_NOT_MODELED",
                "supply.workspace.customs.documentChecklistNotModeled"));
            if (supportingAvailability == SupplyDataQuality.AUTHORITATIVE) {
                supportingAvailability = SupplyDataQuality.PARTIAL;
            }
        }

        SupplyDataQuality availability = count.available()
            ? supportingAvailability
            : SupplyDataQuality.UNAVAILABLE;
        if (!count.available()) {
            reasons.add(reason("SUPPLY_METRIC_SOURCE_FAILED", "supply.workspace.metric.sourceUnavailable"));
        }
        return new WorkspaceMetric(
            key,
            count.value(),
            money,
            quantity,
            unavailableComparison(),
            drilldown(metricQuery, key),
            DEFINITION_VERSION,
            availability,
            reasons);
    }

    private CountResult count(SupplyWorkspaceQuery query, List<PartialError> partialErrors) {
        try {
            return new CountResult(repository.count(query), true);
        } catch (DataAccessException exception) {
            partialErrors.add(sourceFailure(query.queue(), "SUPPLY_METRIC_SOURCE_FAILED"));
            return new CountResult(null, false);
        }
    }

    private List<AgendaDaySummary> agenda(SupplyWorkspaceQuery query, List<PartialError> partialErrors) {
        LocalDate from = query.asOfDate();
        LocalDate to = from.plusDays(8);
        try {
            return repository.agendaByDay(query, from, to).stream()
                .map(day -> new AgendaDaySummary(
                    day.date(), day.count(), day.highestSeverity(),
                    new DrilldownTarget(null, appliedFilters(query),
                        "/app/category/menuSupply/calendar?date=" + day.date())))
                .toList();
        } catch (DataAccessException exception) {
            partialErrors.add(new PartialError(
                "SUPPLY_AGENDA", "SUPPLY_AGENDA_SOURCE_FAILED",
                "supply.workspace.agenda.sourceUnavailable", true));
            return List.of();
        }
    }

    private void appendUnlinkedImportCoverage(
        SupplyWorkspaceQuery query,
        List<PartialError> partialErrors
    ) {
        try {
            long unlinked = repository.countUnlinkedImportRisks(query);
            if (unlinked > 0) {
                partialErrors.add(new PartialError(
                    "IMPORT_DELIVERY",
                    "UNLINKED_IMPORT_DELIVERY",
                    "supply.workspace.import.unlinkedRiskExcluded",
                    false));
            }
        } catch (DataAccessException exception) {
            partialErrors.add(new PartialError(
                "IMPORT_DELIVERY",
                "UNLINKED_IMPORT_COVERAGE_UNAVAILABLE",
                "supply.workspace.import.coverageUnavailable",
                true));
        }
    }

    private SupplyCommitmentRow row(SupplyCommitmentRecord record, SupplyWorkspaceQuery query) {
        String encodedKey = keyCodec.encode(record.key());
        List<UnavailableReason> limitations = new ArrayList<>();
        AnalyticsAvailability asnReadiness;
        if (record.key().kind() == SupplyCommitmentKind.SCHEDULE) {
            UnavailableReason headerOnly = reason(
                "HEADER_ONLY_SCHEDULE_ALLOCATION",
                "supply.workspace.asnReadiness.headerOnlyScheduleAllocation");
            asnReadiness = new AnalyticsAvailability(
                "ASN_READINESS", SupplyDataQuality.PARTIAL, List.of(headerOnly));
            limitations.add(headerOnly);
        } else {
            asnReadiness = new AnalyticsAvailability(
                "ASN_READINESS", SupplyDataQuality.PARTIAL,
                List.of(reason("PO_LEVEL_ASN_READINESS_ONLY", "supply.workspace.asnReadiness.poLevelOnly")));
        }
        if (record.currencyCode() == null) {
            limitations.add(reason("MISSING_CURRENCY", "supply.workspace.money.currencyMissing"));
        } else if (!SupplyMoneyService.isIsoCurrency(record.currencyCode())) {
            limitations.add(reason("INVALID_CURRENCY", "supply.workspace.money.currencyInvalid"));
        }
        if (record.openAmount() == null) {
            limitations.add(reason("INCOMPLETE_OPEN_AMOUNT", "supply.workspace.money.amountIncomplete"));
        }
        if (record.plannedQuantity() == null) {
            limitations.add(reason(
                "PLANNED_QUANTITY_UNAVAILABLE",
                "supply.workspace.quantity.plannedUnavailable"));
        }
        if (record.acceptedQuantity() == null) {
            limitations.add(reason(
                "ACCEPTED_QUANTITY_UNAVAILABLE",
                "supply.workspace.quantity.acceptedUnavailable"));
        }
        if (record.operationalDate() != null && record.operationalDate().isBefore(query.asOfDate())
            && record.hasOpenQuantity() == null) {
            limitations.add(reason(
                "OPEN_QUANTITY_ELIGIBILITY_UNAVAILABLE",
                "supply.workspace.overdue.openQuantityEligibilityUnavailable"));
        }
        if (record.isCustomsDocumentBlocked()) {
            limitations.add(reason(
                "DOCUMENT_CHECKLIST_NOT_MODELED",
                "supply.workspace.customs.documentChecklistNotModeled"));
        }
        limitations.add(reason(
            "DESTINATION_WAREHOUSE_NOT_RESOLVED",
            "supply.workspace.destinationWarehouse.notSingularOrMissing"));

        MoneyValue openAmount = new MoneyValue(
            record.openAmount(), record.currencyCode(), null, null, null, null, null, false);
        QuantityValue plannedQuantity = new QuantityValue(record.plannedQuantity(), record.uomCode());
        QuantityValue openQuantity = new QuantityValue(record.openQuantity(), record.uomCode());
        QuantityValue acceptedQuantity = new QuantityValue(record.acceptedQuantity(), record.uomCode());
        CommitmentQuantities quantities = new CommitmentQuantities(
            plannedQuantity, openQuantity, acceptedQuantity);
        AssignmentSummary assignment = assignment(record);
        List<AllowedAction> actions = actionPolicy.actions(record, encodedKey);
        DeepLinkTarget primary = actions.get(0).target();
        Blocker blocker = blocker(record);
        return new SupplyCommitmentRow(
            record.key().canonical(),
            encodedKey,
            new EntityRef(
                record.purchaseOrderId(), record.purchaseOrderCode(), record.purchaseOrderName(), record.status()),
            new EntityRef(record.supplierId(), record.supplierCode(), record.supplierName(), null),
            null,
            record.contractId(),
            record.operationalDate(),
            slaPolicy.overdueDays(record, query.asOfDate()),
            openAmount,
            quantities,
            record.status(),
            blocker.code(),
            blocker.messageKey(),
            record.severity(),
            record.dataQuality(),
            asnReadiness,
            limitations,
            assignment,
            actions,
            primary);
    }

    private Blocker blocker(SupplyCommitmentRecord record) {
        if (record.isIntegrationFailed()) {
            return new Blocker("INTEGRATION_FAILED", "supply.workspace.blocker.integrationFailed");
        }
        if (record.isCustomsDocumentBlocked()) {
            return new Blocker("CUSTOMS_DOCUMENT_BLOCKED", "supply.workspace.blocker.customsBlocked");
        }
        if (record.isActiveImportEtaRisk()) {
            return new Blocker("ACTIVE_IMPORT_ETA_RISK", "supply.workspace.blocker.importEtaRisk");
        }
        if (record.isOverdueCommitment()) {
            return new Blocker("OVERDUE_COMMITMENT", "supply.workspace.blocker.overdue");
        }
        if (record.isArriving7dWithoutConfirmedAsn()) {
            return new Blocker(
                "ARRIVING_7D_WITHOUT_CONFIRMED_ASN",
                "supply.workspace.blocker.asnMissing");
        }
        if (record.isUnassigned()) {
            return new Blocker("UNASSIGNED", "supply.workspace.blocker.unassigned");
        }
        return new Blocker(null, null);
    }

    private AssignmentSummary assignment(SupplyCommitmentRecord record) {
        SupplySeverity assignmentSeverity = record.assignmentSeverity() == null
            ? record.severity()
            : record.assignmentSeverity();
        if (record.isUnassigned()) {
            return new AssignmentSummary(
                null, null, record.assignmentDueDate(), record.assignmentDueDateSource(), assignmentSeverity,
                record.assignmentEscalationState(), record.assignmentEscalationLevel(), record.assignmentReason(),
                record.assignmentVersion(),
                SupplyDataQuality.AUTHORITATIVE, List.of());
        }
        EntityRef owner = new EntityRef(
            record.ownerUserId(), record.ownerUsername(), record.ownerUsername(), null);
        return new AssignmentSummary(
            owner, record.ownerRole(), record.assignmentDueDate(), record.assignmentDueDateSource(),
            assignmentSeverity, record.assignmentEscalationState(), record.assignmentEscalationLevel(),
            record.assignmentReason(), record.assignmentVersion(),
            SupplyDataQuality.AUTHORITATIVE, List.of());
    }

    private AppliedScope appliedScope(SupplyWorkspaceQuery query) {
        return new AppliedScope(
            query.tenantId(), query.authorizedCompanyIds(), query.authorizedWarehouseIds(), query.warehouseScoped());
    }

    private DrilldownTarget drilldown(SupplyWorkspaceQuery query, SupplyQueueKey queue) {
        return new DrilldownTarget(queue, appliedFilters(query), COMMITMENTS_ROUTE);
    }

    private MetricComparison unavailableComparison() {
        return new MetricComparison(
            false, null, null,
            reason(
                "NO_REPRODUCIBLE_HISTORICAL_COHORT",
                "supply.workspace.metric.comparison.unavailableHistoricalCohort"));
    }

    private PartialError sourceFailure(SupplyQueueKey key, String code) {
        return new PartialError(
            key == null ? "SUPPLY_COMMITMENT" : "SUPPLY_COMMITMENT:" + key,
            code,
            "supply.workspace.metric.sourceUnavailable",
            true);
    }

    private SourceFreshness freshness(Instant generatedAt, List<PartialError> partialErrors) {
        boolean partial = partialErrors != null && !partialErrors.isEmpty();
        return new SourceFreshness(
            "CANONICAL_SUPPLY_COMMITMENT",
            generatedAt,
            partial ? null : generatedAt,
            partial ? SupplySourceState.PARTIAL : SupplySourceState.FRESH,
            partial ? "supply.workspace.source.partial" : "supply.workspace.source.available");
    }

    private SupplySeverity queueSeverity(SupplyQueueKey key) {
        return switch (key) {
            case OVERDUE_COMMITMENTS, ACTIVE_IMPORT_ETA_RISK,
                 CUSTOMS_DOCUMENT_BLOCKED, INTEGRATION_FAILED -> SupplySeverity.BLOCKING;
            case ARRIVING_7D_WITHOUT_CONFIRMED_ASN, UNASSIGNED -> SupplySeverity.WARNING;
        };
    }

    private SupplyDataQuality queueAvailability(SupplyQueueKey key, CountResult count) {
        if (!count.available()) return SupplyDataQuality.UNAVAILABLE;
        return key == SupplyQueueKey.OVERDUE_COMMITMENTS
            || key == SupplyQueueKey.CUSTOMS_DOCUMENT_BLOCKED
            ? SupplyDataQuality.PARTIAL
            : SupplyDataQuality.AUTHORITATIVE;
    }

    private UnavailableReason reason(String code, String messageKey) {
        return new UnavailableReason(code, messageKey);
    }

    private void requireQuery(SupplyWorkspaceQuery query) {
        if (query == null || query.tenantId() == null || query.asOf() == null
            || query.asOfDate() == null || query.timeZone() == null) {
            throw new IllegalArgumentException("Normalized supply workspace query is required");
        }
    }

    private SupplyWorkspaceQuery effectiveQuery(SupplyWorkspaceQuery query) {
        requireQuery(query);
        accessPolicy.requireReadable(query.lens());
        SupplyWorkspaceQuery bound = accessPolicy.bindAuthenticatedIdentity(query);
        return bound == null ? query : bound;
    }

    private record CountResult(Long value, boolean available) {
    }

    private record Blocker(String code, String messageKey) {
    }
}
