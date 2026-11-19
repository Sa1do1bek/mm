package com.enterprise.mm.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.enterprise.mm.overview.payload.OperationalOverviewDtos.Health;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.OperationalOverview;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.SourceHealth;
import com.enterprise.mm.overview.payload.OperationalOverviewDtos.SourceStatus;
import com.enterprise.mm.overview.service.OperationalOverviewDataSource;
import com.enterprise.mm.overview.service.OperationalOverviewDataSource.OperationalFacts;
import com.enterprise.mm.overview.service.OperationalOverviewService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@SpringJUnitConfig(OperationalOverviewTransactionIsolationTest.Config.class)
class OperationalOverviewTransactionIsolationTest {
    private static final Instant NOW = Instant.parse("2026-07-16T04:00:00Z");

    @Autowired
    private TransactionalOverviewCaller caller;

    @Test
    void aDegradedSourceDoesNotMarkTheWholeOverviewRollbackOnly() {
        AtomicReference<OperationalOverview> result = new AtomicReference<>();

        assertThatCode(() -> result.set(caller.overview())).doesNotThrowAnyException();

        assertThat(result.get()).isNotNull();
        assertThat(result.get().metadata().status()).isEqualTo(Health.PARTIAL);
        assertThat(result.get().sourceHealth())
            .extracting(SourceHealth::key, SourceHealth::status)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("inventory", SourceStatus.UNAVAILABLE));
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        FailingTransactionalSource failingTransactionalSource() {
            return new FailingTransactionalSource();
        }

        @Bean
        OperationalOverviewDataSource overviewDataSource(FailingTransactionalSource failingSource) {
            return () -> {
                try {
                    failingSource.load();
                } catch (RuntimeException ignored) {
                    // A dashboard source is deliberately allowed to degrade independently.
                }
                return new OperationalFacts(
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(new SourceHealth(
                        "inventory",
                        "Inventory availability",
                        SourceStatus.UNAVAILABLE,
                        "Inventory source unavailable",
                        null,
                        "/app/stock-balances"
                    )),
                    List.of()
                );
            };
        }

        @Bean
        OperationalOverviewService operationalOverviewService(OperationalOverviewDataSource dataSource) {
            return new OperationalOverviewService(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        TransactionalOverviewCaller transactionalOverviewCaller(OperationalOverviewService service) {
            return new TransactionalOverviewCaller(service);
        }
    }

    static class TransactionalOverviewCaller {
        private final OperationalOverviewService service;

        TransactionalOverviewCaller(OperationalOverviewService service) {
            this.service = service;
        }

        @Transactional(readOnly = true)
        public OperationalOverview overview() {
            return service.overview();
        }
    }

    static class FailingTransactionalSource {
        @Transactional(readOnly = true)
        public void load() {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw new IllegalStateException("simulated source failure");
        }
    }
}
