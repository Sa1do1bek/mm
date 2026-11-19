package com.enterprise.mm.warehouse.history;

import java.nio.file.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

@org.testcontainers.junit.jupiter.Testcontainers
class WarehouseProjectionMigrationRunnerTest {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres=new org.testcontainers.containers.PostgreSQLContainer<>("postgres:17-alpine");
    @TempDir Path migrations;
    JdbcTemplate jdbc;
    String location(){return "filesystem:"+migrations;}
    @BeforeEach void setup() throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        for(int version=1;version<=8;version++)Files.writeString(migrations.resolve("V"+version+"__fixture.sql"),"create table original_"+version+"(id int);insert into original_"+version+" values (123);");
        Files.writeString(migrations.resolve("V9__projection.sql"),"create table projection_only(id int);insert into projection_only values (9);");
        Files.writeString(migrations.resolve("V10__later.sql"),"create table later_only(id int);");
    }
    void version8(){Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations(location()).target("8").load().migrate();}
    void migrate(){WarehouseProjectionMigrationRunner.migrate(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword(),location());}
    @AfterEach void cleanup(){jdbc.execute("drop schema public cascade");jdbc.execute("create schema public");}
    @Test void appliesOnly9AndPreservesExistingTables(){
        version8();migrate();
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history",Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("select count(*) from projection_only",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select to_regclass('later_only')",String.class)).isNull();
        for(int i=1;i<=8;i++)assertThat(jdbc.queryForObject("select id from original_"+i,Integer.class)).isEqualTo(123);
        assertThatThrownBy(this::migrate).isInstanceOf(IllegalStateException.class);
    }
    @Test void refusesFreshOrChangedInstalledMigrations() throws Exception {
        assertThatThrownBy(this::migrate).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select to_regclass('projection_only')",String.class)).isNull();
        version8();Files.writeString(migrations.resolve("V1__fixture.sql"),"drop table original_1;");
        assertThatThrownBy(this::migrate).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select id from original_1",Integer.class)).isEqualTo(123);
    }
    @Test void failedProjectionRollsBackAndRetainsVersion8() throws Exception {
        version8();Files.writeString(migrations.resolve("V9__projection.sql"),"create table projection_only(id int);insert into projection_only values (9);select 1/0;");
        assertThatThrownBy(this::migrate).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history",Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select to_regclass('projection_only')",String.class)).isNull();
    }
}
