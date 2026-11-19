package com.enterprise.mm.erp;
import static org.assertj.core.api.Assertions.assertThat;
import java.sql.ResultSet;
import java.sql.DriverManager;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class ErpExchangeMigrationPostgresTest {
    @Test void upgradesAnExistingVersion78DatabaseThroughCurrentMigrations() throws Exception {
        String url=System.getenv("ERP_MIGRATION_POSTGRES_URL"), username="mm_local", password="";
        PostgreSQLContainer<?> container=null;
        if (url != null && !url.isBlank()) {
            if (!url.equals("jdbc:postgresql://127.0.0.1:15439/mm_erp_review_full_20261115"))
                throw new IllegalArgumentException("External migration test requires the dedicated empty review DB");
        } else {
            container=new PostgreSQLContainer<>("postgres:16-alpine"); container.start();
            url=container.getJdbcUrl(); username=container.getUsername(); password=container.getPassword();
        }
        try {
            var before=Flyway.configure().dataSource(url,username,password)
                .configuration(Map.of("flyway.postgresql.transactional.lock","false")).target("78").load();
            before.migrate(); before.validate();
            assertThat(before.info().current().getScript()).isEqualTo("V78__damaged_stock_cases.sql");
            var after=Flyway.configure().dataSource(url,username,password)
                .configuration(Map.of("flyway.postgresql.transactional.lock","false")).load();
            assertThat(after.migrate().migrationsExecuted).isEqualTo(7);
            after.validate(); assertThat(after.info().current().getScript()).isEqualTo("V90__erp_delivery_order.sql");
            try (var connection=DriverManager.getConnection(url,username,password); var statement=connection.createStatement();
                 ResultSet result=statement.executeQuery("select to_regclass('public.damaged_stock_cases'), to_regclass('public.erp_exchange_connection')")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("damaged_stock_cases");
                assertThat(result.getString(2)).isEqualTo("erp_exchange_connection");
            }
        } finally { if(container!=null) container.stop(); }
    }
}
