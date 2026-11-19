package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ManagementOpportunityArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/enterprise/mm");
    private static final List<String> FORBIDDEN_COUPLINGS = List.of(
        "StockValuationService",
        "InventoryCostingService",
        "StockMovementService",
        "StockValuationDocumentService",
        "IntegrationEventService"
    );

    @Test
    void managementOpportunityCodeDoesNotCoupleToPostingOrValuationServices() throws Exception {
        if (!Files.exists(SOURCE_ROOT)) {
            return;
        }

        List<Path> files = Files.walk(SOURCE_ROOT)
            .filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().startsWith("ManagementOpportunity"))
            .filter(path -> path.getFileName().toString().endsWith(".java"))
            .toList();

        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (String forbidden : FORBIDDEN_COUPLINGS) {
                assertThat(source)
                    .as("%s must not reference %s", file, forbidden)
                    .doesNotContain(forbidden);
            }
        }
    }

    @Test
    void managementOpportunityTextUsesManagementEstimateLanguage() throws Exception {
        if (!Files.exists(SOURCE_ROOT)) {
            return;
        }

        String combined = String.join("\n", Files.walk(SOURCE_ROOT)
            .filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().startsWith("ManagementOpportunity"))
            .filter(path -> path.getFileName().toString().endsWith(".java"))
            .map(path -> {
                try {
                    return Files.readString(path, StandardCharsets.UTF_8);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            })
            .toList()).toLowerCase(Locale.ROOT);

        assertThat(combined).doesNotContain("accounting posting");
        assertThat(combined).doesNotContain("ifrs");
        assertThat(combined).doesNotContain("journal");
    }

    @Test
    void operationsDocumentationStatesNoAccountingPosting() throws Exception {
        Path doc = Path.of("docs/management-opportunity-analytics-operations.md");

        assertThat(doc).exists();
        String content = Files.readString(doc, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertThat(content).contains("management estimates");
        assertThat(content).contains("does not create accounting postings");
    }
}
