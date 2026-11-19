package com.enterprise.mm.overview.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.AgingBucket;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.Health;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.Metadata;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.OperationalOverview;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.Outcome;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.ProcessStage;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.PriorityException;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.RawException;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.SourceHealth;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.SourceDrilldown;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.SourceStatus;
import com.enterprise.mm.overview.service.OperationalOverviewDataSource.OperationalFacts;
import com.enterprise.mm.overview.service.OperationalOverviewDataSource.CohortSource;
import com.enterprise.mm.security.CompanyScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalOverviewService {
    private static final String COVERAGE_URL = "/app/stock-balances?queue=SHORTAGE";

    private final OperationalOverviewDataSource dataSource;
    private final Clock clock;

    @Autowired
    public OperationalOverviewService(OperationalOverviewDataSource dataSource) {
        this(dataSource, Clock.systemDefaultZone());
    }

    public OperationalOverviewService(OperationalOverviewDataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OperationalOverview overview() {
        LocalDate today = LocalDate.now(clock);
        Instant generatedAt = Instant.now(clock);
        OperationalFacts facts = dataSource.load();
        List<String> messages = facts.sourceHealth().stream()
            .filter(item -> item.status() != SourceStatus.HEALTHY)
            .map(SourceHealth::message)
            .filter(message -> message != null && !message.isBlank())
            .distinct()
            .toList();
        Health metadataHealth = facts.sourceHealth().stream()
            .anyMatch(item -> item.status() != SourceStatus.HEALTHY) ? Health.PARTIAL : Health.READY;

        List<PriorityException> exceptions = facts.priorityExceptions().stream()
            .map(item -> toPriorityException(item, today))
            .sorted(priorityComparator())
            .limit(25)
            .toList();

        return new OperationalOverview(
            new Metadata(
                generatedAt,
                null,
                null,
                "CURRENT_SNAPSHOT",
                generatedAt,
                CompanyContext.currentCompany(),
                "COMPANY",
                metadataHealth,
                messages
            ),
            outcomes(facts),
            scopedStages(facts.stages()),
            aging(exceptions),
            facts.trend(),
            scopedSourceHealth(facts.sourceHealth()),
            exceptions
        );
    }

    private List<Outcome> outcomes(OperationalFacts facts) {
        Outcome overdue = countOutcome(
            "critical_overdue",
            "Критически просрочено",
            facts.criticalOverdue(),
            "Текущие просроченные закупочные, приёмочные, качественные и интеграционные исключения без двойного учёта внутри источника.",
            "CRITICAL_OVERDUE",
            facts.criticalOverdueSources()
        );
        Outcome blocked = countOutcome(
            "blocked_flow",
            "Поток заблокирован",
            facts.blockedFlow(),
            "Текущие блокировки, требующие решения владельца процесса до продолжения движения материалов.",
            "BLOCKED_FLOW",
            facts.blockedFlowSources()
        );
        BigDecimal coverage = null;
        Health coverageHealth = Health.UNAVAILABLE;
        if (facts.demandMaterialCount() != null && facts.shortageMaterialCount() != null
            && facts.demandMaterialCount() > 0) {
            long covered = Math.max(0L, facts.demandMaterialCount() - facts.shortageMaterialCount());
            coverage = BigDecimal.valueOf(covered)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(facts.demandMaterialCount()), 2, RoundingMode.HALF_UP);
            coverageHealth = Health.READY;
        }
        Outcome demandCoverage = new Outcome(
            "demand_coverage",
            "Материалы без дефицита",
            coverage,
            "PERCENT",
            coverageHealth,
            "Доля активных складских позиций, для которых прогнозный доступный остаток покрывает открытый спрос.",
            "INVENTORY_SHORTAGE",
            CompanyScope.currentCompanyDrilldown(COVERAGE_URL),
            List.of()
        );
        return List.of(overdue, blocked, demandCoverage);
    }

    private Outcome countOutcome(
        String key,
        String label,
        Long value,
        String definition,
        String queueKey,
        List<CohortSource> sources
    ) {
        return new Outcome(
            key,
            label,
            value == null ? null : BigDecimal.valueOf(value),
            "COUNT",
            value == null ? Health.UNAVAILABLE : Health.READY,
            definition,
            queueKey,
            null,
            sources.stream().map(source -> new SourceDrilldown(
                source.key(),
                source.label(),
                source.count(),
                source.count() == null ? Health.UNAVAILABLE : Health.READY,
                CompanyScope.currentCompanyDrilldown(source.drilldownUrl())
            )).toList()
        );
    }

    private List<AgingBucket> aging(List<PriorityException> exceptions) {
        long due02 = exceptions.stream().filter(item -> item.ageDays() != null
            && item.ageDays() >= 0 && item.ageDays() <= 2).count();
        long overdue37 = exceptions.stream().filter(item -> item.ageDays() != null
            && item.ageDays() >= 3 && item.ageDays() <= 7).count();
        long overdue814 = exceptions.stream().filter(item -> item.ageDays() != null
            && item.ageDays() >= 8 && item.ageDays() <= 14).count();
        long overdue15 = exceptions.stream().filter(item -> item.ageDays() != null
            && item.ageDays() >= 15).count();
        long undated = exceptions.stream().filter(item -> item.ageDays() == null).count();
        return List.of(
            bucket("DUE_0_2", "Просрочено до 2 дней", due02, "INFO"),
            bucket("OVERDUE_3_7", "Просрочено 3–7 дней", overdue37, "WARNING"),
            bucket("OVERDUE_8_14", "Просрочено 8–14 дней", overdue814, "WARNING"),
            bucket("OVERDUE_15_PLUS", "Просрочено 15+ дней", overdue15, "CRITICAL"),
            bucket("NO_DUE_DATE", "Срок не указан", undated, "WARNING")
        );
    }

    private AgingBucket bucket(String key, String label, long count, String severity) {
        return new AgingBucket(
            key,
            label,
            count,
            severity,
            "PRIORITY_EXCEPTION_SAMPLE",
            CompanyScope.currentCompanyDrilldown("/app/dashboard?ageBucket=" + key)
        );
    }

    private PriorityException toPriorityException(RawException item, LocalDate today) {
        Long ageDays = item.dueAt() == null
            ? null
            : Math.max(0, ChronoUnit.DAYS.between(item.dueAt(), today));
        return new PriorityException(
            item.id(),
            item.queueKey(),
            item.title(),
            item.severity(),
            item.impact(),
            item.owner(),
            item.dueAt(),
            ageDays,
            item.nextAction(),
            item.sourceModule(),
            CompanyScope.currentCompanyDrilldown(item.drilldownUrl()),
            item.status()
        );
    }

    private List<ProcessStage> scopedStages(List<ProcessStage> stages) {
        return stages.stream().map(item -> new ProcessStage(
            item.key(),
            item.label(),
            item.count(),
            item.health(),
            item.queueKey(),
            CompanyScope.currentCompanyDrilldown(item.drilldownUrl())
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

    private Comparator<PriorityException> priorityComparator() {
        return Comparator
            .comparingInt((PriorityException item) -> severityRank(item.severity()))
            .thenComparing(
                PriorityException::ageDays,
                Comparator.nullsLast(Comparator.reverseOrder())
            )
            .thenComparing(PriorityException::id, Comparator.nullsLast(String::compareTo));
    }

    private int severityRank(String severity) {
        if ("CRITICAL".equalsIgnoreCase(severity)) {
            return 0;
        }
        if ("WARNING".equalsIgnoreCase(severity)) {
            return 1;
        }
        return 2;
    }
}
