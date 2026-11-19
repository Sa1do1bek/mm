package com.enterprise.mm.integration.kimyosanoat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class KimyosanoatIssueClaimPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    @BeforeAll static void database() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V81__kimyosanoat_issue_links.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    private int claim(long id) {
        return jdbc.update("""
            insert into kimyosanoat_issue_links(source_transfer_id, tenant_id, company_id, warehouse_id, material_id, source_fingerprint, source_snapshot, created_by)
            values (?, 'tenant', ?, ?, ?, 'fingerprint', '{}'::jsonb, 'manager') on conflict (source_transfer_id) do nothing
            """, id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
    @Test void concurrentApprovalsCanClaimSourceOnlyOnce() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(() -> tx.execute(status -> claim(101)), () -> tx.execute(status -> claim(101))));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(0, 1);
        }
        assertThat(jdbc.queryForObject("select count(*) from kimyosanoat_issue_links where source_transfer_id=101", Integer.class)).isEqualTo(1);
    }
    @Test void failedPostingRollsBackClaimAndAllowsRetry() {
        assertThatThrownBy(() -> tx.execute(status -> { claim(102); throw new IllegalStateException("posting failed"); })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from kimyosanoat_issue_links where source_transfer_id=102", Integer.class)).isZero();
        Integer retried = tx.execute(status -> claim(102));
        assertThat(retried).isEqualTo(1);
    }
}
