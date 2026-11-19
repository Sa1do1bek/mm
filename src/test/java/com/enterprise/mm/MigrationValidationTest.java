package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@EntityScan(basePackages = {
    "com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common",
    "com.enterprise.mm.warehouse"
})
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate"
})
class MigrationValidationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID TARGET_COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;
    private static final List<String> COMPANY_DEPARTMENT_TYPE_CODES = List.of(
        "ADMINISTRATIVE",
        "FUNCTIONAL",
        "PRODUCTION",
        "COMMERCIAL",
        "LOGISTICS",
        "TECHNICAL",
        "BRANCH"
    );

    @Autowired
    JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @Test
    void flywaySchemaMatchesJpaModel() {
    }

    @Test
    void flywaySeedsMainDepartmentTypesForTargetCompany() {
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*)
                from department_types
                where tenant_id = 'default'
                  and company_id = ?
                  and is_delete = false
                  and code in (?, ?, ?, ?, ?, ?, ?)
                """,
            Integer.class,
            TARGET_COMPANY_ID,
            COMPANY_DEPARTMENT_TYPE_CODES.get(0),
            COMPANY_DEPARTMENT_TYPE_CODES.get(1),
            COMPANY_DEPARTMENT_TYPE_CODES.get(2),
            COMPANY_DEPARTMENT_TYPE_CODES.get(3),
            COMPANY_DEPARTMENT_TYPE_CODES.get(4),
            COMPANY_DEPARTMENT_TYPE_CODES.get(5),
            COMPANY_DEPARTMENT_TYPE_CODES.get(6)
        );

        assertThat(count).isEqualTo(COMPANY_DEPARTMENT_TYPE_CODES.size());
    }

    @Test
    void rfqAggregationPartialUniqueIndexesExist() {
        List<String> indexNames = jdbcTemplate.queryForList(
            """
                select indexname
                from pg_indexes
                where schemaname = current_schema()
                  and indexname in (
                    'uk_rfq_pr_source_active_item',
                    'uk_contract_active_source_rfq',
                    'uk_contract_active_source_quotation',
                    'uk_contract_spec_source_quote_item'
                  )
                """,
            String.class
        );

        assertThat(indexNames).contains(
            "uk_rfq_pr_source_active_item",
            "uk_contract_active_source_rfq",
            "uk_contract_active_source_quotation",
            "uk_contract_spec_source_quote_item");
    }

    @Test
    void stockBalanceLogicalKeyIsNullSafeAndAppliesOnlyToActiveRows() {
        String indexDefinition = jdbcTemplate.queryForObject(
            """
                select indexdef
                from pg_indexes
                where schemaname = 'public'
                  and tablename = 'stock_balances'
                  and indexname = 'uk_stock_balance_key'
                """,
            String.class
        );

        assertThat(indexDefinition)
            .containsIgnoringCase("unique index")
            .containsIgnoringCase("nulls not distinct")
            .containsIgnoringCase("where (is_delete = false)");
    }

    @Test
    void serializedTmbWave1SchemaMatchesJpaAndIndexes() {
        List<String> tables = jdbcTemplate.queryForList(
            """
                select table_name
                from information_schema.tables
                where table_schema = current_schema()
                  and table_name in ('goods_receipt_units', 'material_units', 'material_unit_events')
                """,
            String.class
        );
        assertThat(tables).contains("goods_receipt_units", "material_units", "material_unit_events");

        List<String> materialColumns = jdbcTemplate.queryForList(
            """
                select column_name
                from information_schema.columns
                where table_schema = current_schema()
                  and table_name = 'material_units'
                """,
            String.class
        );
        assertThat(materialColumns)
            .contains(
                "material_id",
                "manufacturer_serial_number",
                "normalized_serial_number",
                "unit_status",
                "stock_batch_id",
                "goods_receipt_line_id",
                "goods_receipt_unit_id",
                "received_at")
            .doesNotContain("quantity");

        List<String> indexes = jdbcTemplate.queryForList(
            """
                select indexname
                from pg_indexes
                where schemaname = current_schema()
                  and indexname in (
                    'uk_material_units_company_material_serial',
                    'uk_material_units_receipt_unit',
                    'ix_goods_receipt_units_line',
                    'ix_material_unit_events_unit'
                  )
                """,
            String.class
        );
        assertThat(indexes).contains(
            "uk_material_units_company_material_serial",
            "uk_material_units_receipt_unit",
            "ix_goods_receipt_units_line",
            "ix_material_unit_events_unit");

        String serialIndex = jdbcTemplate.queryForObject(
            """
                select indexdef
                from pg_indexes
                where schemaname = current_schema()
                  and indexname = 'uk_material_units_company_material_serial'
                """,
            String.class
        );
        assertThat(serialIndex)
            .containsIgnoringCase("unique index")
            .contains("tenant_id", "company_id", "material_id", "normalized_serial_number")
            .containsIgnoringCase("where (is_delete = false)");
    }
}
