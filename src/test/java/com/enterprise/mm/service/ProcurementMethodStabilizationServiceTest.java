package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcurementMethodStabilizationServiceTest {
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query query = mock(Query.class);
    private final ProcurementMethodStabilizationService service = new ProcurementMethodStabilizationService(entityManager);

    @BeforeEach
    void setContext() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void classificationDryRunReportsEveryTaskSixRiskBucketWithoutMutatingData() {
        AtomicInteger count = new AtomicInteger();
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getSingleResult()).thenAnswer(invocation -> count.incrementAndGet());

        var report = service.classifyCurrentCompany();

        assertThat(report.rows())
            .extracting(row -> row.issueCode())
            .containsExactly(
                "AUCTION_NON_COMPLETED",
                "AUCTION_COMPLETED",
                "HISTORICAL_METHOD_ALIAS",
                "HISTORICAL_METHOD_ALIAS",
                "MISSING_CAPABILITY_SNAPSHOT",
                "FRAMEWORK_CALLOFF_WITHOUT_LEDGER",
                "DEPRECATED_PATH_AWARD"
            );
        assertThat(report.rows())
            .extracting(row -> row.classification())
            .contains(
                "REQUIRES_MANUAL_REVIEW",
                "GRANDFATHERED",
                "AUTO_MIGRATABLE",
                "REQUIRES_CONFIGURATION",
                "INVALID"
            );
        assertThat(report.rows()).last().satisfies(row -> assertThat(row.count()).isEqualTo(7));
    }

    @Test
    void usageReportReturnsCapabilityConfigurationAndMethodCounts() {
        Query capabilityQuery = mock(Query.class);
        Query usageQuery = mock(Query.class);
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("select method_code,\n")))
            .thenReturn(capabilityQuery);
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.contains("with methods as")))
            .thenReturn(usageQuery);
        when(capabilityQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(capabilityQuery);
        when(usageQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(usageQuery);
        when(capabilityQuery.getResultList()).thenReturn(java.util.Collections.singletonList(new Object[] {
            "DIRECT_PURCHASE",
            "ENABLED",
            Date.valueOf(LocalDate.of(2026, 1, 1)),
            Date.valueOf(LocalDate.of(2026, 12, 31)),
            BigDecimal.ONE,
            BigDecimal.TEN,
            "UZS",
            "Configured",
            3
        }));
        when(usageQuery.getResultList()).thenReturn(java.util.Collections.singletonList(new Object[] {
            "DIRECT_PURCHASE",
            2,
            3,
            4,
            5,
            0,
            0
        }));

        var report = service.usageCurrentCompany();

        assertThat(report.capabilities()).singleElement().satisfies(row -> {
            assertThat(row.methodCode()).isEqualTo("DIRECT_PURCHASE");
            assertThat(row.state()).isEqualTo("ENABLED");
            assertThat(row.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(row.effectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
            assertThat(row.minAmount()).isEqualByComparingTo("1");
            assertThat(row.maxAmount()).isEqualByComparingTo("10");
            assertThat(row.currencyCode()).isEqualTo("UZS");
            assertThat(row.reason()).isEqualTo("Configured");
            assertThat(row.configurationVersion()).isEqualTo(3);
        });
        assertThat(report.usage()).singleElement().satisfies(row -> {
            assertThat(row.methodCode()).isEqualTo("DIRECT_PURCHASE");
            assertThat(row.methodDecisionCount()).isEqualTo(2);
            assertThat(row.procedureCount()).isEqualTo(3);
            assertThat(row.awardOutcomeCount()).isEqualTo(4);
            assertThat(row.directAwardCount()).isEqualTo(5);
        });
    }
}
