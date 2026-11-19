package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.MaterialCommandViewDtos.MetricStatus;
import com.enterprise.mm.payload.MaterialStockDetailDtos.Filter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The same business/security scenarios execute against the production database dialect. */
@Testcontainers(disabledWithoutDocker = true)
class MaterialStockDetailPostgresTest extends MaterialStockDetailScenarios {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override void initializeDatabase() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
    }

    @Test void migrationPreservesLegacyRowsWithoutInventingAnOriginalPrice() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into stock_movements(id,tenant_id,company_id,material_id,quantity,unit_cost) values (?,'t',?,?,10,100)",
            id, company, material);
        jdbc.execute("alter table stock_movements drop column source_unit_price_snapshot");
        jdbc.execute("alter table goods_receipt_batches drop column stock_movement_id");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V52__material_receipt_detail_snapshots.sql"))
            .execute(jdbc.getDataSource());
        assertThat(jdbc.queryForObject("select source_unit_price_snapshot from stock_movements where id=?", Object.class, id)).isNull();
        assertThat(jdbc.queryForObject("select quantity from stock_movements where id=?", java.math.BigDecimal.class, id))
            .isEqualByComparingTo("10");
        assertThat(service.detail(material, new Filter(warehouse, null, null, true), 0, 25)
            .summary().onHand().status()).isEqualTo(MetricStatus.AVAILABLE);
    }
}
