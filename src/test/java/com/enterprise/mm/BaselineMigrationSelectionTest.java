package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BaselineMigrationSelectionTest {
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    void versionedMigrationsHaveUniqueVersions() throws Exception {
        try (var files = Files.list(MIGRATIONS)) {
            var versions = files.map(path -> path.getFileName().toString())
                .filter(name -> name.matches("V[0-9]+__.*\\.sql"))
                .collect(Collectors.groupingBy(name -> name.substring(1, name.indexOf("__")), Collectors.counting()));
            assertThat(versions).allSatisfy((version, count) -> assertThat(count)
                .as("Flyway version %s must be unique", version).isEqualTo(1L));
        }
    }

    @Test
    void shipsTheNavoiyazotBaselineAndItsFirstTwoFollowUpMigrations() throws Exception {
        try (var files = Files.list(MIGRATIONS)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                .contains(
                    "V1__baseline_schema.sql",
                    "V2__material_incoming_inspection_requirement.sql",
                    "V3__demand_approved_stock_portion.sql"
                );
        }
    }

    @Test
    void recentMigrationsExtendTablesPresentInTheBaseline() throws Exception {
        String baseline = Files.readString(MIGRATIONS.resolve("V1__baseline_schema.sql"));
        String material = Files.readString(MIGRATIONS.resolve("V2__material_incoming_inspection_requirement.sql"));
        String demand = Files.readString(MIGRATIONS.resolve("V3__demand_approved_stock_portion.sql"));

        assertThat(baseline).contains("CREATE TABLE public.materials (", "CREATE TABLE public.demand_plan_items (");
        assertThat(baseline).doesNotContain("incoming_inspection_required", "stock_fulfillment_quantity");
        assertThat(material).contains("ALTER TABLE materials ADD COLUMN incoming_inspection_required");
        assertThat(demand).contains("ALTER TABLE demand_plan_items ADD COLUMN stock_fulfillment_quantity");
    }
}
