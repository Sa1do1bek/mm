package com.enterprise.mm;

import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ContractGroupLinesMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void forwardMigrationPreservesExistingLinesAndEnforcesExclusiveReferences() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).target("42").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String spec = "11111111-1111-1111-1111-111111111111";
        jdbc.execute("insert into contract_specifications(id, tenant_id, company_id, created_at, version, name, status, contract_id, line_type, service_limit) "
            + "values ('" + spec + "', 'group-test', gen_random_uuid(), now(), 0, 'Existing line', 'DRAFT', gen_random_uuid(), 'MATERIAL', 0)");
        var migration = Flyway.configure().dataSource(dataSource).target("43").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        migration.validate();
        assertThat(jdbc.queryForObject("select count(*) from contract_specifications where id='" + spec + "' and material_group_id is null and unit_of_measure_id is null", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_name='stock_movements' and column_name='contract_specification_id'", String.class)).isEqualTo("YES");
        assertThatThrownBy(() -> jdbc.execute("update contract_specifications set material_id=gen_random_uuid(), material_type_snapshot='TMB', material_group_id=gen_random_uuid() where id='" + spec + "'"))
            .hasMessageContaining("ck_contract_specification_material_or_group");
    }
}
