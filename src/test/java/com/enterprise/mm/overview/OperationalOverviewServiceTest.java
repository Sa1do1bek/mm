package com.enterprise.mm.overview;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.Health;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.ProcessStage;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.RawException;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.SourceHealth;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.SourceStatus;
import com.enterprise.mm.overview.service.OperationalOverviewDataSource;
import com.enterprise.mm.overview.service.OperationalOverviewDataSource.CohortSource;
import com.enterprise.mm.overview.service.OperationalOverviewDataSource.OperationalFacts;
import com.enterprise.mm.overview.service.OperationalOverviewService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OperationalOverviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-15T08:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID COMPANY_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearContext() {
        CompanyContext.clear();
    }

    @Test
    void computesDecisionMetricsAndAgingFromExplicitFacts() {
        CompanyContext.setCompany(COMPANY_ID);
        OperationalOverviewDataSource source = () -> new OperationalFacts(
            7L,
            4L,
            50L,
            10L,
            List.of(
                new CohortSource("procurement", "Закупки", 3L, "/app/procurement-command?queue=OVERDUE_WORK"),
                new CohortSource("quality", "Качество", 4L, "/app/quality-inspections?queue=OVERDUE")
            ),
            List.of(new CohortSource(
                "procurement", "Закупки", 4L, "/app/procurement-command?queue=PO_DISPATCH_BLOCKED")),
            List.of(new ProcessStage("REQUEST", "Заявки", 12, Health.READY,
                "OPEN_PR", "/app/purchase-requests?status=DRAFT&status=APPROVAL_PENDING&status=APPROVED")),
            List.of(),
            List.of(new SourceHealth("procurement", "Закупки", SourceStatus.HEALTHY,
                null, NOW, "/app/procurement-command")),
            List.of(
                exception("critical-old", "CRITICAL", LocalDate.of(2026, 6, 25)),
                exception("warning-new", "WARNING", LocalDate.of(2026, 7, 14)),
                exception("undated", "WARNING", null)
            )
        );

        var result = new OperationalOverviewService(source, CLOCK).overview();

        assertThat(result.metadata().generatedAt()).isEqualTo(NOW);
        assertThat(result.metadata().companyId()).isEqualTo(COMPANY_ID);
        assertThat(result.metadata().dataBasis()).isEqualTo("CURRENT_SNAPSHOT");
        assertThat(result.metadata().asOf()).isEqualTo(NOW);
        assertThat(result.metadata().periodFrom()).isNull();
        assertThat(result.metadata().periodTo()).isNull();
        assertThat(result.metadata().status()).isEqualTo(Health.READY);
        assertThat(result.outcomes()).extracting(item -> item.key())
            .containsExactly("critical_overdue", "blocked_flow", "demand_coverage");
        assertThat(result.outcomes().get(2).value()).isEqualByComparingTo("80.00");
        assertThat(result.outcomes().get(2).unit()).isEqualTo("PERCENT");
        assertThat(result.outcomes().get(0).drilldownUrl()).isNull();
        assertThat(result.outcomes().get(0).drilldowns())
            .extracting(item -> item.key(), item -> item.count(), item -> item.drilldownUrl())
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                    "procurement", 3L,
                    "/app/procurement-command?queue=OVERDUE_WORK&companyScope=CURRENT_COMPANY"),
                org.assertj.core.groups.Tuple.tuple(
                    "quality", 4L,
                    "/app/quality-inspections?queue=OVERDUE&companyScope=CURRENT_COMPANY")
            );
        assertThat(result.outcomes().get(2).drilldownUrl())
            .isEqualTo("/app/stock-balances?queue=SHORTAGE&companyScope=CURRENT_COMPANY");
        assertThat(result.stages()).extracting(ProcessStage::drilldownUrl)
            .containsExactly(
                "/app/purchase-requests?status=DRAFT&status=APPROVAL_PENDING&status=APPROVED"
                    + "&companyScope=CURRENT_COMPANY");
        assertThat(result.sourceHealth()).extracting(SourceHealth::drilldownUrl)
            .containsExactly("/app/procurement-command?companyScope=CURRENT_COMPANY");
        assertThat(result.aging()).extracting(item -> item.key(), item -> item.count())
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("DUE_0_2", 1L),
                org.assertj.core.groups.Tuple.tuple("OVERDUE_3_7", 0L),
                org.assertj.core.groups.Tuple.tuple("OVERDUE_8_14", 0L),
                org.assertj.core.groups.Tuple.tuple("OVERDUE_15_PLUS", 1L),
                org.assertj.core.groups.Tuple.tuple("NO_DUE_DATE", 1L)
            );
        assertThat(result.aging()).extracting(item -> item.drilldownUrl())
            .containsExactly(
                "/app/dashboard?ageBucket=DUE_0_2&companyScope=CURRENT_COMPANY",
                "/app/dashboard?ageBucket=OVERDUE_3_7&companyScope=CURRENT_COMPANY",
                "/app/dashboard?ageBucket=OVERDUE_8_14&companyScope=CURRENT_COMPANY",
                "/app/dashboard?ageBucket=OVERDUE_15_PLUS&companyScope=CURRENT_COMPANY",
                "/app/dashboard?ageBucket=NO_DUE_DATE&companyScope=CURRENT_COMPANY"
            );
        assertThat(result.priorityExceptions()).extracting(item -> item.id())
            .containsExactly("critical-old", "warning-new", "undated");
        assertThat(result.priorityExceptions()).extracting(item -> item.drilldownUrl())
            .containsOnly("/app/procurement-command?queue=OVERDUE_WORK&companyScope=CURRENT_COMPANY");
        assertThat(result.priorityExceptions()).filteredOn(item -> item.id().equals("undated"))
            .extracting(item -> item.ageDays())
            .containsExactly((Long) null);
    }

    @Test
    void exposesUnavailableMetricsInsteadOfTurningMissingDataIntoZero() {
        OperationalOverviewDataSource source = () -> new OperationalFacts(
            null,
            0L,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(new SourceHealth("procurement", "Закупки", SourceStatus.UNAVAILABLE,
                "Источник временно недоступен", null, "/app/procurement-command")),
            List.of()
        );

        var result = new OperationalOverviewService(source, CLOCK).overview();

        assertThat(result.metadata().status()).isEqualTo(Health.PARTIAL);
        assertThat(result.metadata().messages()).contains("Источник временно недоступен");
        assertThat(result.outcomes().get(0).health()).isEqualTo(Health.UNAVAILABLE);
        assertThat(result.outcomes().get(0).value()).isNull();
        assertThat(result.outcomes().get(1).health()).isEqualTo(Health.READY);
        assertThat(result.outcomes().get(1).value()).isEqualByComparingTo("0");
        assertThat(result.outcomes().get(2).health()).isEqualTo(Health.UNAVAILABLE);
    }

    private static RawException exception(String id, String severity, LocalDate dueAt) {
        return new RawException(
            id,
            "QUEUE",
            "Exception " + id,
            severity,
            "Остановка процесса",
            "MM_MANAGER",
            dueAt,
            "Проверить",
            "PROCUREMENT",
            "/app/procurement-command?queue=OVERDUE_WORK",
            "OPEN"
        );
    }
}
