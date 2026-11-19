package com.enterprise.mm.service;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Same endpoint/filter contracts against real PostgreSQL and the deployed Flyway schema. */
@Testcontainers
@EntityScan(basePackages = {"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class StockExpiryPostgresTest extends StockExpiryIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }
}
