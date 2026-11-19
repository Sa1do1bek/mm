package com.enterprise.mm.service;

import com.enterprise.mm.entity.ManagementOpportunity;
import com.enterprise.mm.enums.ManagementOpportunityDataCompleteness;
import com.enterprise.mm.enums.ManagementOpportunityStatus;
import com.enterprise.mm.payload.ManagementOpportunityBreakdownResponse;
import com.enterprise.mm.payload.ManagementOpportunityBreakdownResponse.CaseDrilldown;
import com.enterprise.mm.payload.ManagementOpportunityBreakdownResponse.EntityAmount;
import com.enterprise.mm.payload.ManagementOpportunityBreakdownResponse.GroupAmount;
import com.enterprise.mm.payload.ManagementOpportunitySummaryResponse;
import com.enterprise.mm.payload.ManagementOpportunitySummaryResponse.AgingBucket;
import com.enterprise.mm.payload.ManagementOpportunitySummaryResponse.ConversionMetadata;
import com.enterprise.mm.payload.ManagementOpportunitySummaryResponse.CountBreakdown;
import com.enterprise.mm.payload.ManagementOpportunitySummaryResponse.CurrencyAmount;
import com.enterprise.mm.payload.ManagementOpportunitySummaryResponse.WaterfallCurrency;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;

/** Pure aggregation rules for the management-opportunity reporting read model. */
final class ManagementOpportunityAnalytics {
    private static final String REALIZATION_NOTE =
        "Realized value is unavailable until a realization lifecycle is recorded.";

    private final Clock clock;

    ManagementOpportunityAnalytics(Clock clock) {
        this.clock = clock;
    }

    ManagementOpportunitySummaryResponse summary(List<ManagementOpportunity> source) {
        List<ManagementOpportunity> cases = latestSnapshots(source);
        List<ManagementOpportunity> approved = approvedCases(cases);
        List<CurrencyAmount> approvedCurrencies = currencyAmounts(
            approved,
            ManagementOpportunity::getNormalizedCurrencyCode,
            ManagementOpportunity::getNormalizedAmount
        );
        BigDecimal compatibleApprovedAmount = approvedCurrencies.size() > 1
            ? null
            : approvedCurrencies.stream().findFirst().map(CurrencyAmount::amount).orElse(BigDecimal.ZERO);
        String compatibleApprovedCurrency = approvedCurrencies.size() == 1
            ? approvedCurrencies.get(0).currencyCode()
            : null;

        return new ManagementOpportunitySummaryResponse(
            compatibleApprovedAmount,
            compatibleApprovedCurrency,
            approvedCurrencies.size() > 1,
            approved.size(),
            countStatus(cases, ManagementOpportunityStatus.READY_FOR_REVIEW),
            cases.stream().filter(item -> item.getDataCompleteness() == ManagementOpportunityDataCompleteness.DATA_INCOMPLETE).count(),
            missingConversionCount(cases),
            clock.instant(),
            approvedCurrencies,
            currencyAmounts(cases, ManagementOpportunity::getSourceCurrencyCode, ManagementOpportunity::getSourceAmount),
            waterfall(cases, approved),
            statusBreakdown(cases),
            completenessBreakdown(cases),
            aging(cases),
            conversion(cases),
            groupAmounts(approved, item -> item.getOpportunityType() == null ? null : item.getOpportunityType().name()),
            groupAmounts(approved, item -> item.getConfidence() == null ? null : item.getConfidence().name()),
            entityAmounts(approved, ManagementOpportunity::getSupplierId),
            entityAmounts(approved, ManagementOpportunity::getWarehouseId),
            List.of()
        );
    }

    ManagementOpportunityBreakdownResponse breakdown(
        List<ManagementOpportunity> source,
        Map<UUID, List<UUID>> evidenceIdsByCase,
        Pageable pageable
    ) {
        return breakdown(source, evidenceIdsByCase, Map.of(), pageable);
    }

    ManagementOpportunityBreakdownResponse breakdown(
        List<ManagementOpportunity> source,
        Map<UUID, List<UUID>> evidenceIdsByCase,
        Map<UUID, UUID> materialGroupByMaterial,
        Pageable pageable
    ) {
        List<ManagementOpportunity> approved = approvedCases(latestSnapshots(source));
        List<ManagementOpportunity> ordered = approved.stream()
            .sorted(latestFirst())
            .toList();
        List<ManagementOpportunity> page = slice(ordered, pageable);

        return new ManagementOpportunityBreakdownResponse(
            groupAmounts(approved, item -> item.getOpportunityType() == null ? null : item.getOpportunityType().name()),
            groupAmounts(approved, item -> item.getConfidence() == null ? null : item.getConfidence().name()),
            entityAmounts(approved, ManagementOpportunity::getSupplierId),
            entityAmounts(approved, ManagementOpportunity::getWarehouseId),
            entityAmounts(approved, ManagementOpportunity::getMaterialId),
            entityAmounts(
                approved,
                item -> item.getMaterialId() == null ? null : materialGroupByMaterial.get(item.getMaterialId())
            ),
            page.stream().map(item -> caseDrilldown(item, evidenceIdsByCase)).toList()
        );
    }

    List<ManagementOpportunity> latestSnapshots(List<ManagementOpportunity> source) {
        Map<BusinessCaseKey, ManagementOpportunity> latest = new LinkedHashMap<>();
        for (ManagementOpportunity item : source == null ? List.<ManagementOpportunity>of() : source) {
            BusinessCaseKey key = new BusinessCaseKey(
                item.getOpportunityType() == null ? null : item.getOpportunityType().name(),
                item.getSourceAggregateType(),
                item.getSourceAggregateId(),
                item.getSourceLineId()
            );
            latest.merge(key, item, (left, right) -> snapshotComparator().compare(left, right) >= 0 ? left : right);
        }
        return List.copyOf(latest.values());
    }

    private List<ManagementOpportunity> approvedCases(List<ManagementOpportunity> cases) {
        return cases.stream()
            .filter(item -> ManagementOpportunityStatus.APPROVED.name().equals(item.getStatus()))
            .filter(item -> item.getDataCompleteness() == ManagementOpportunityDataCompleteness.COMPLETE)
            .filter(item -> item.getNormalizedAmount() != null)
            .filter(item -> normalizedCurrency(item.getNormalizedCurrencyCode()) != null)
            .toList();
    }

    private List<CurrencyAmount> currencyAmounts(
        List<ManagementOpportunity> cases,
        Function<ManagementOpportunity, String> currency,
        Function<ManagementOpportunity, BigDecimal> amount
    ) {
        Map<String, AmountCount> grouped = new LinkedHashMap<>();
        for (ManagementOpportunity item : cases) {
            String code = normalizedCurrency(currency.apply(item));
            BigDecimal value = amount.apply(item);
            if (code == null || value == null) {
                continue;
            }
            grouped.computeIfAbsent(code, ignored -> new AmountCount()).add(value);
        }
        return grouped.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new CurrencyAmount(entry.getKey(), entry.getValue().amount, entry.getValue().count))
            .toList();
    }

    private List<WaterfallCurrency> waterfall(
        List<ManagementOpportunity> cases,
        List<ManagementOpportunity> approved
    ) {
        Set<String> currencies = cases.stream()
            .map(ManagementOpportunity::getNormalizedCurrencyCode)
            .map(this::normalizedCurrency)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return currencies.stream().sorted().map(currency -> {
            List<ManagementOpportunity> detected = cases.stream()
                .filter(item -> currency.equals(normalizedCurrency(item.getNormalizedCurrencyCode())))
                .filter(item -> item.getNormalizedAmount() != null)
                .toList();
            List<ManagementOpportunity> normalized = detected.stream()
                .filter(item -> item.getDataCompleteness() == ManagementOpportunityDataCompleteness.COMPLETE)
                .toList();
            List<ManagementOpportunity> approvedInCurrency = approved.stream()
                .filter(item -> currency.equals(normalizedCurrency(item.getNormalizedCurrencyCode())))
                .toList();
            return new WaterfallCurrency(
                currency,
                sumNormalized(detected),
                sumNormalized(normalized),
                sumNormalized(approvedInCurrency),
                null,
                detected.size(),
                normalized.size(),
                approvedInCurrency.size(),
                0,
                false
            );
        }).toList();
    }

    private List<CountBreakdown> statusBreakdown(List<ManagementOpportunity> cases) {
        return EnumSet.allOf(ManagementOpportunityStatus.class).stream()
            .map(status -> new CountBreakdown(status.name(), countStatus(cases, status)))
            .toList();
    }

    private List<CountBreakdown> completenessBreakdown(List<ManagementOpportunity> cases) {
        return EnumSet.allOf(ManagementOpportunityDataCompleteness.class).stream()
            .map(completeness -> new CountBreakdown(
                completeness.name(),
                cases.stream().filter(item -> item.getDataCompleteness() == completeness).count()
            ))
            .toList();
    }

    private List<AgingBucket> aging(List<ManagementOpportunity> cases) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("0_7_DAYS", 0L);
        counts.put("8_30_DAYS", 0L);
        counts.put("31_60_DAYS", 0L);
        counts.put("61_PLUS_DAYS", 0L);
        LocalDate today = LocalDate.now(clock);
        for (ManagementOpportunity item : cases) {
            LocalDate created = item.getCreatedAt() == null
                ? item.getAsOfDate()
                : item.getCreatedAt().toLocalDate();
            long days = created == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(created, today));
            String bucket = days <= 7
                ? "0_7_DAYS"
                : days <= 30 ? "8_30_DAYS" : days <= 60 ? "31_60_DAYS" : "61_PLUS_DAYS";
            counts.computeIfPresent(bucket, (ignored, count) -> count + 1);
        }
        return counts.entrySet().stream().map(entry -> new AgingBucket(entry.getKey(), entry.getValue())).toList();
    }

    private ConversionMetadata conversion(List<ManagementOpportunity> cases) {
        List<ManagementOpportunity> converted = cases.stream()
            .filter(item -> item.getNormalizedAmount() != null)
            .filter(item -> normalizedCurrency(item.getSourceCurrencyCode()) != null)
            .filter(item -> normalizedCurrency(item.getNormalizedCurrencyCode()) != null)
            .filter(item -> !normalizedCurrency(item.getSourceCurrencyCode()).equals(normalizedCurrency(item.getNormalizedCurrencyCode())))
            .toList();
        long sameCurrency = cases.stream()
            .filter(item -> item.getNormalizedAmount() != null)
            .filter(item -> normalizedCurrency(item.getSourceCurrencyCode()) != null)
            .filter(item -> normalizedCurrency(item.getSourceCurrencyCode()).equals(normalizedCurrency(item.getNormalizedCurrencyCode())))
            .count();
        List<LocalDate> rateDates = converted.stream()
            .map(ManagementOpportunity::getExchangeRateDate)
            .filter(Objects::nonNull)
            .sorted()
            .toList();
        List<String> rateSources = converted.stream()
            .map(ManagementOpportunity::getExchangeRateSource)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .sorted()
            .toList();
        return new ConversionMetadata(
            "STORED_CASE_NORMALIZATION",
            converted.size(),
            sameCurrency,
            missingConversionCount(cases),
            rateDates.isEmpty() ? null : rateDates.get(0),
            rateDates.isEmpty() ? null : rateDates.get(rateDates.size() - 1),
            rateSources,
            REALIZATION_NOTE
        );
    }

    private List<GroupAmount> groupAmounts(
        List<ManagementOpportunity> cases,
        Function<ManagementOpportunity, String> group
    ) {
        Map<GroupCurrencyKey, AmountCount> grouped = new LinkedHashMap<>();
        for (ManagementOpportunity item : cases) {
            String groupKey = group.apply(item);
            String currency = normalizedCurrency(item.getNormalizedCurrencyCode());
            if (groupKey == null || currency == null || item.getNormalizedAmount() == null) {
                continue;
            }
            grouped.computeIfAbsent(new GroupCurrencyKey(groupKey, currency), ignored -> new AmountCount())
                .add(item.getNormalizedAmount());
        }
        return grouped.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new GroupAmount(
                entry.getKey().group,
                entry.getKey().currency,
                entry.getValue().amount,
                entry.getValue().count
            ))
            .toList();
    }

    private List<EntityAmount> entityAmounts(
        List<ManagementOpportunity> cases,
        Function<ManagementOpportunity, UUID> entityId
    ) {
        Map<EntityCurrencyKey, AmountCount> grouped = new LinkedHashMap<>();
        for (ManagementOpportunity item : cases) {
            UUID id = entityId.apply(item);
            String currency = normalizedCurrency(item.getNormalizedCurrencyCode());
            if (id == null || currency == null || item.getNormalizedAmount() == null) {
                continue;
            }
            grouped.computeIfAbsent(new EntityCurrencyKey(id, currency), ignored -> new AmountCount())
                .add(item.getNormalizedAmount());
        }
        return grouped.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new EntityAmount(
                entry.getKey().id,
                entry.getKey().currency,
                entry.getValue().amount,
                entry.getValue().count
            ))
            .toList();
    }

    private CaseDrilldown caseDrilldown(
        ManagementOpportunity item,
        Map<UUID, List<UUID>> evidenceIdsByCase
    ) {
        String group = item.getOpportunityType() == null ? "UNKNOWN" : item.getOpportunityType().name();
        return new CaseDrilldown(
            item.getId(),
            group,
            group,
            item.getStatus(),
            item.getDataCompleteness() == null ? null : item.getDataCompleteness().name(),
            item.getAssignedRole(),
            item.getAsOfDate(),
            item.getSourceAggregateType(),
            item.getSourceAggregateId(),
            item.getSourceLineId(),
            item.getFormulaCode(),
            item.getFormulaSnapshot(),
            ManagementOpportunityFormulaCatalog.describe(item.getOpportunityType()),
            item.getSourceCurrencyCode(),
            item.getSourceAmount(),
            item.getNormalizedCurrencyCode(),
            item.getNormalizedAmount(),
            evidenceIdsByCase.getOrDefault(item.getId(), List.of()),
            "/app/management-opportunities?tab=cases&caseId=" + item.getId()
        );
    }

    private List<ManagementOpportunity> slice(List<ManagementOpportunity> values, Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return values;
        }
        long offset = pageable.getOffset();
        if (offset >= values.size()) {
            return List.of();
        }
        int from = Math.toIntExact(offset);
        int to = Math.min(values.size(), from + pageable.getPageSize());
        return new ArrayList<>(values.subList(from, to));
    }

    private Comparator<ManagementOpportunity> snapshotComparator() {
        return Comparator
            .comparing(ManagementOpportunity::getAsOfDate, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ManagementOpportunity::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(item -> item.getId() == null ? "" : item.getId().toString());
    }

    private Comparator<ManagementOpportunity> latestFirst() {
        return snapshotComparator().reversed();
    }

    private long countStatus(List<ManagementOpportunity> cases, ManagementOpportunityStatus status) {
        return cases.stream().filter(item -> status.name().equals(item.getStatus())).count();
    }

    private long missingConversionCount(List<ManagementOpportunity> cases) {
        return cases.stream()
            .filter(item -> item.getDataCompleteness() == ManagementOpportunityDataCompleteness.DATA_INCOMPLETE)
            .filter(item -> item.getNormalizedAmount() == null || normalizedCurrency(item.getNormalizedCurrencyCode()) == null)
            .count();
    }

    private BigDecimal sumNormalized(List<ManagementOpportunity> cases) {
        return cases.stream()
            .map(ManagementOpportunity::getNormalizedAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String normalizedCurrency(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private record BusinessCaseKey(String type, String sourceType, UUID sourceId, UUID sourceLineId) {
    }

    private record GroupCurrencyKey(String group, String currency) implements Comparable<GroupCurrencyKey> {
        @Override
        public int compareTo(GroupCurrencyKey other) {
            int groupOrder = group.compareTo(other.group);
            return groupOrder == 0 ? currency.compareTo(other.currency) : groupOrder;
        }
    }

    private record EntityCurrencyKey(UUID id, String currency) implements Comparable<EntityCurrencyKey> {
        @Override
        public int compareTo(EntityCurrencyKey other) {
            int idOrder = id.compareTo(other.id);
            return idOrder == 0 ? currency.compareTo(other.currency) : idOrder;
        }
    }

    private static final class AmountCount {
        private BigDecimal amount = BigDecimal.ZERO;
        private long count;

        private void add(BigDecimal value) {
            amount = amount.add(value);
            count++;
        }
    }
}
