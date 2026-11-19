package com.enterprise.mm.warehouse.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"app.seed.enabled=false", "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=none",
    "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false"})
@ActiveProfiles("test")
@EntityScan(basePackages = {"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
@Testcontainers
@Transactional
class ReturnablePackagingRegisterQueryTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    @Autowired ReturnablePackagingLotRepository lots;
    @Autowired ReturnablePackagingReturnRepository returns;
    @MockBean com.enterprise.mm.config.AiAnalysisJobRecovery aiJobRecovery;
    @MockBean com.enterprise.mm.config.StockValuationCostJobRecovery costJobRecovery;

    @Test void lotAndReturnRegisterQueriesExecuteWithOptionalFiltersAndStableSort() {
        UUID company = UUID.randomUUID();
        assertThat(lots.search("packaging-query", company, null, null, null, null,
            null, null, null, null, null, false, null,
            PageRequest.of(0, 20, Sort.by("createdAt").descending().and(Sort.by("id"))))).isEmpty();
        assertThat(returns.search("packaging-query", company, null, null, null, null,
            null, null, PageRequest.of(0, 20, Sort.by("createdAt").descending().and(Sort.by("id")))))
            .isEmpty();
        assertThat(lots.search("packaging-query", company, null, null, null, null,
            "SUPPLIER", "PURCHASE_ORDER", "EMPTY", null, null, true, "%drum%",
            PageRequest.of(0, 20, Sort.by("dueDate").ascending().and(Sort.by("id")))))
            .isEmpty();
        assertThat(returns.search("packaging-query", company, null, null, null,
            "DISPATCHED", "SUPPLIER", "%drum%", PageRequest.of(0, 20, Sort.by("quantity").descending())))
            .isEmpty();
    }
}
